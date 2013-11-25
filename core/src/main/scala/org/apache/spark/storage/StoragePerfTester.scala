package org.apache.spark.storage

import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.{CountDownLatch, Executors}

import org.apache.spark.serializer.KryoSerializer
import org.apache.spark.SparkContext
import org.apache.spark.util.Utils

/**
 * Utility for micro-benchmarking shuffle write performance.
 *
 * Writes simulated shuffle output from several threads and records the observed throughput.
 */
object StoragePerfTester {
  def main(args: Array[String]) = {
    /** Amount of data to generate per shuffle. Distributed amongst maps and reduce splits. */
    val dataSizeMb = Utils.memoryStringToMb(sys.env.getOrElse("OUTPUT_DATA", "1g"))

    /** Number of map tasks. */
    val numMaps = sys.env.get("NUM_MAPS").map(_.toInt).getOrElse(8)

    /** Number of concurrent maps to run. */
    val numSlots = sys.env.get("NUM_SLOTS").map(_.toInt).getOrElse(8)

    /** Number of reduce splits for each map task. */
    val numOutputSplits = sys.env.get("NUM_REDUCERS").map(_.toInt).getOrElse(500)

    /** Number of shuffles to run.. */
    val numShuffles = sys.env.get("NUM_SHUFFLES").map(_.toInt).getOrElse(1)

    val recordLength = 1000 // ~1KB records
    val totalRecords = dataSizeMb * 1000
    val recordsPerMap = totalRecords / numMaps

    val writeData = "1" * recordLength

    System.setProperty("spark.shuffle.compress", "false")
    System.setProperty("spark.shuffle.sync", "false")

    // This is only used to instantiate a BlockManager. All thread scheduling is done manually.
    val sc = new SparkContext("local[4]", "Write Tester")
    val blockManager = sc.env.blockManager

    def writeOutputBytes(shuffleId: Int, mapId: Int, total: AtomicLong) = {
      val shuffle = blockManager.shuffleBlockManager.forShuffle(shuffleId, numOutputSplits,
        new KryoSerializer())
      val buckets = shuffle.acquireWriters(mapId)
      for (i <- 1 to recordsPerMap) {
        buckets.writers(i % numOutputSplits).write(writeData)
      }
      buckets.writers.map {w =>
        w.commit()
        total.addAndGet(w.fileSegment().length)
        w.close()
      }

      shuffle.releaseWriters(buckets)
    }

    for (s <- 0 until numShuffles) {
      val executor = Executors.newFixedThreadPool(numSlots)
      val start = System.currentTimeMillis()
      val latch = new CountDownLatch(numMaps)
      val totalBytes = new AtomicLong()
      for (task <- 1 to numMaps) {
        executor.submit(new Runnable() {
          override def run() = {
            try {
              writeOutputBytes(s, task, totalBytes)
              latch.countDown()
            } catch {
              case e: Exception =>
                println("Exception in child thread: " + e + " " + e.getMessage)
                System.exit(1)
            }
          }
        })
      }
      latch.await()
      val end = System.currentTimeMillis()
      val time = (end - start) / 1000.0
      val bytesPerSecond = totalBytes.get() / time
      val totalFiles = numSlots * numOutputSplits
      val bytesPerFile = (totalBytes.get() / totalFiles.toDouble).toLong

      System.err.println("shuffle\t\t%s".format(s))
      System.err.println("files_total\t\t%s".format(totalFiles))
      System.err.println("bytes_per_file\t\t%s".format(Utils.bytesToString(bytesPerFile)))
      System.err.println("agg_throughput\t\t%s/s".format(Utils.bytesToString(bytesPerSecond.toLong)))
      System.err.println("")

      executor.shutdown()
      sc.stop()
    }
  }
}
