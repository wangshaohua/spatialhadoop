package org.apache.hadoop.spatial;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.fs.FSDataOutputStream;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.io.Text;

public class RTreeGridRecordWriter<S extends Shape> extends GridRecordWriter<S> {
  public static final Log LOG = LogFactory.getLog(RTreeGridRecordWriter.class);
  
  /**
   * Keeps the number of elements written to each cell so far.
   * Helps calculating the overhead of RTree indexing
   */
  private int[] cellCount;

  /**The degree of the trees built*/
  private final int rtreeDegree;
  
  /**
   * Whether to use the fast mode for building RTree or not.
   * @see RTree#bulkLoadWrite(byte[], int, int, int, java.io.DataOutput, boolean)
   */
  protected boolean fastRTree;

  /**
   * Initializes a new RTreeGridRecordWriter.
   * @param fileSystem - of output file
   * @param outFile - output file path
   * @param cells - the cells used to partition the input
   * @param overwrite - whether to overwrite existing files or not
   * @throws IOException
   */
  public RTreeGridRecordWriter(FileSystem fileSystem, Path outFile,
      CellInfo[] cells, boolean overwrite) throws IOException {
    super(fileSystem, outFile, cells, overwrite);
    LOG.info("Writing to RTrees");

    // Initialize the counters for each cell
    cellCount = new int[cells.length];
    
    // Determine the size of each RTree to decide when to flush a cell
    this.rtreeDegree = fileSystem.getConf().getInt(SpatialSite.RTREE_DEGREE, 25);
    this.fastRTree = fileSystem.getConf().get(SpatialSite.RTREE_BUILD_MODE, "fast").equals("fast");
  }
  
  @Override
  protected synchronized void writeInternal(int cellIndex, Text text)
      throws IOException {
    if (text.getLength() == 0) {
      // Write to parent cell which will close the index
      super.writeInternal(cellIndex, text);
      return;
    }
    FSDataOutputStream cellOutput = (FSDataOutputStream) getCellStream(cellIndex);

    // Check if the RTree will overflow when writing this new object
    int storage_overhead = RTree.calculateStorageOverhead(
        cellCount[cellIndex] + 1, rtreeDegree);
    // Calculate the expected RTree file size after writing this new object
    // 8: File signature
    // storage_overhead: Total size of the RTree after adding this object
    // cellOutput.getPos(): Current data size
    // text.getLength() + NEW_LINE.length: New object data size
    long rtree_file_size = 8 + storage_overhead + cellOutput.getPos()
        + text.getLength() + NEW_LINE.length;
    if (rtree_file_size > blockSize) {
      // Write an empty line to close the cell
      super.writeInternal(cellIndex, new Text());
      return;
    }

    super.writeInternal(cellIndex, text);
    cellCount[cellIndex]++;
  }
  
  @Override
  protected int getMaxConcurrentThreads() {
    // Since the closing cell is memory intensive, limit it to one
    return 1;
  }

  
  /**
   * Closes a cell by writing all outstanding objects and closing current file.
   * Then, the file is read again, an RTree is built on top of it and, finally,
   * the file is written again with the RTree built.
   */
  @Override
  protected void closeCell(int cellIndex) throws IOException {
    // close current stream.
    // PS: No need to stuff it with new lines as it is only a temporary file
    OutputStream tempCellStream = cellStreams[cellIndex];
    tempCellStream.close();
    
    // Read all data of the written file in memory
    Path cellFile = cellFilePath[cellIndex];
    long length = fileSystem.getFileStatus(cellFile).getLen();
    byte[] cellData = new byte[(int) length];
    InputStream cellIn = fileSystem.open(cellFile);
    cellIn.read(cellData);
    cellIn.close();
    // Delete the file to be able recreate it when written as an RTree
    fileSystem.delete(cellFile, true);
    
    // Build an RTree over the elements read from file
    RTree<S> rtree = new RTree<S>();
    rtree.setStockObject(stockObject);
    // Set cellStream to null to ensure that getCellStream will create a new one
    cellStreams[cellIndex] = null;
    // It should create a new stream
    FSDataOutputStream cellStream = (FSDataOutputStream) getCellStream(cellIndex);
    cellStream.writeLong(SpatialSite.RTreeFileMarker);
    rtree.bulkLoadWrite(cellData, 0, (int) length, rtreeDegree, cellStream,
        fastRTree);
    cellData = null; // To allow GC to collect it
    // Call the parent implementation which will stuff it with new lines
    super.closeCell(cellIndex);
    cellCount[cellIndex] = 0;
  }
  
/*  
  public static void main(String[] args) throws IOException {
    Configuration conf = new Configuration();
    FileSystem fs = FileSystem.getLocal(conf);
    Path outFile = new Path("test.rtree");
    GridInfo gridInfo = new GridInfo(0, 0, 1000000, 100000);
    gridInfo.columns = 1;
    gridInfo.rows = 1;
    CellInfo[] cells = gridInfo.getAllCells();
    RTreeGridRecordWriter<Rectangle> recordWriter = new RTreeGridRecordWriter<Rectangle>(fs, outFile,
        cells, true);
    recordWriter.setStockObject(new Rectangle());
    long recordCount = 1000000;
    Random random = new Random();
    System.out.println("Creating "+recordCount+" records");
    long t1 = System.currentTimeMillis();
    Rectangle s = new Rectangle();
    for (CellInfo cellInfo : cells) {
      Rectangle mbr = cellInfo;
      for (int i = 0; i < recordCount; i++) {
        // Generate a random rectangle
        s.x = Math.abs(random.nextLong() % mbr.width) + mbr.x;
        s.y = Math.abs(random.nextLong() % mbr.height) + mbr.y;
        s.width = Math.min(Math.abs(random.nextLong() % 100) + 1,
            mbr.width + mbr.x - s.x);
        s.height = Math.min(Math.abs(random.nextLong() % 100) + 1,
            mbr.height + mbr.y - s.y);
        
        recordWriter.write(cellInfo, s);
      }
      recordWriter.write(cellInfo, null);
    }
    recordWriter.close(null);
    long t2 = System.currentTimeMillis();
    System.out.println("Finished in "+(t2-t1)+" millis");
    //System.out.println("Final size: "+fs.getFileStatus(outFile).getLen());
  }
*/
}
