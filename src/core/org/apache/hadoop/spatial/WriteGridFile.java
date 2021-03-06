package org.apache.hadoop.spatial;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.fs.FSDataInputStream;
import org.apache.hadoop.fs.FileStatus;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.io.Text;
import org.apache.hadoop.util.LineReader;

/**
 * 
 * @author eldawy
 * @deprecated - Use Repartition class instead
 */
@Deprecated
public class WriteGridFile {
  public static final Log LOG = LogFactory.getLog(WriteGridFile.class);

  /**
   * Writes a grid file to HDFS.
   * 
   * @param inFileSystem
   * @param inputPath
   * @param outFileSystem
   * @param outputPath
   * @param gridInfo
   * @param shapeClass
   * @param pack
   *          - set to <code>true</code> to pack grid cells in a
   *          distribution-aware manner
   * @param rtree
   *          - set to <code>true</code> to store each grid cell as rtree
   * @throws IOException
   * @throws InstantiationException
   * @throws IllegalAccessException
   */
  public static<S extends Shape> void writeGridFile(FileSystem inFileSystem,
      Path inputPath, FileSystem outFileSystem, Path outputPath, GridInfo gridInfo,
      S shape, boolean pack, boolean rtree, boolean overwrite)
      throws IOException {
    gridInfo = calculateGridInfo(inFileSystem, inputPath, outFileSystem, gridInfo, shape);
    if (rtree)
      gridInfo.calculateCellDimensions(inFileSystem.getFileStatus(inputPath).getLen() * 4, outFileSystem.getDefaultBlockSize());
    CellInfo[] cells = pack ? packInRectangles(inFileSystem, inputPath, outFileSystem, gridInfo, shape) :
      gridInfo.getAllCells();

    // Prepare grid file writer
    ShapeRecordWriter<S> rrw = rtree ?
        new RTreeGridRecordWriter<S>(outFileSystem, outputPath, cells, overwrite):
        new GridRecordWriter<S>(outFileSystem, outputPath, cells, overwrite);

    // Open input file
    LineReader reader = new LineReader(inFileSystem.open(inputPath));

    Text line = new Text();
    while (reader.readLine(line) > 0) {
      // Parse shape dimensions
      shape.fromText(line);

      // Write to output file
      rrw.write(shape, line);
    }
    
    // Close input file
    reader.close();

    // Close output file
    rrw.close(null);
  }
  
  /**
   * Calculate minimum bounding rectangle of a file by opening it and reading every tuple.
   * @param fileSystem
   * @param path
   * @return
   * @throws IOException
   */
  private static<S extends Shape> Rectangle calculateMBR(FileSystem fileSystem, Path path, S shape) throws IOException {
    LineReader inFile = new LineReader(fileSystem.open(path));
    Rectangle rectangle = new Rectangle();
    Text line = new Text();
    long x1 = Long.MAX_VALUE;
    long x2 = Long.MIN_VALUE;
    long y1 = Long.MAX_VALUE;
    long y2 = Long.MIN_VALUE;
    while (inFile.readLine(line) > 0) {
      // Parse shape dimensions
      shape.fromText(line);

      if (rectangle.getX1() < x1) x1 = rectangle.getX1();
      if (rectangle.getY1() < y1) y1 = rectangle.getY1();
      if (rectangle.getX2() > x2) x2 = rectangle.getX2();
      if (rectangle.getY2() > y2) y2 = rectangle.getY2();
    }

    inFile.close();
    return new Rectangle(x1, y1, x2 - x1 + 1, y2 - y1 + 1);
  }
  
  /**
   * Returns MBR of a file. If the file is a grid file, MBR is calculated from GridInfo
   * associated with the file. With a heap file, the file is parsed, and MBR is calculated.
   * @param fileSystem
   * @param path
   * @return
   * @throws IOException
   */
  public static <S extends Shape> Rectangle getMBR(FileSystem fileSystem,
      Path path, S shape) throws IOException {
    FileStatus fileStatus = fileSystem.getFileStatus(path);
    LOG.info("gridInfo stored in file: " + fileStatus.getGridInfo());
    return fileStatus.getGridInfo() == null ?
        calculateMBR(fileSystem, path, shape) : fileStatus.getGridInfo().getMBR();
  }
  
  /**
   * Calculate GridInfo to be used to write a file using a heuristic.
   * @param inFileSystem
   * @param path
   * @return
   * @throws IOException
   */
  public static<S extends Shape> GridInfo calculateGridInfo(FileSystem inFileSystem, Path path,
      FileSystem outFileSystem, GridInfo gridInfo, S shape) throws IOException {
    if (gridInfo == null) {
      Rectangle mbr = getMBR(inFileSystem, path, shape);
      LOG.info("MBR: "+mbr);
      gridInfo = new GridInfo(mbr.x, mbr.y, mbr.width, mbr.height);
      LOG.info("GridInfo: "+gridInfo);
    }
    if (gridInfo.columns == 0) {
      gridInfo.calculateCellDimensions(inFileSystem.getFileStatus(path).getLen(), outFileSystem.getDefaultBlockSize());
      LOG.info("GridInfo: "+gridInfo);
    }
    return gridInfo;
  }
  
  public static<S extends Shape> GridInfo getGridInfo(FileSystem inFileSystem,Path path,
      FileSystem outFileSystem, S shape) throws IOException {
    FileStatus fileStatus = inFileSystem.getFileStatus(path);
    return calculateGridInfo(inFileSystem, path, outFileSystem,
        fileStatus.getGridInfo(), shape);
  }
  
  private static final int MaxLineLength = 200;
  
  public static <S extends Shape> CellInfo[] packInRectangles(
      FileSystem inFileSystem, Path inputPath, FileSystem outFileSystem,
      GridInfo gridInfo, S shape) throws IOException {
    return packInRectangles(inFileSystem, new Path[] { inputPath },
        outFileSystem, gridInfo, shape);
  }

  public static<S extends Shape> CellInfo[] packInRectangles(FileSystem inFileSystem, Path[] inputPaths,
      FileSystem outFileSystem, GridInfo gridInfo, S shape) throws IOException {
    Point[] samples = pickRandomSample(inFileSystem, inputPaths, shape);
    Rectangle[] rectangles = RTree.packInRectangles(gridInfo, samples);
    CellInfo[] cellsInfo = new CellInfo[rectangles.length];
    for (int i = 0; i < rectangles.length; i++)
      cellsInfo[i] = new CellInfo(i, rectangles[i]);
    return cellsInfo;
  }

  private static<S extends Shape> Point[] pickRandomSample(FileSystem inFileSystem,
      Path[] inputPaths, S shape) throws IOException {
    LOG.info("Picking a random sample from file");
    Random random = new Random();
    long inTotalSize = 0;
    long[] inputSize = new long[inputPaths.length];
    for (int fileIndex = 0; fileIndex < inputPaths.length; fileIndex++) {
      inputSize[fileIndex] = inFileSystem.getFileStatus(inputPaths[fileIndex]).getLen();
      inTotalSize += inputSize[fileIndex];
    }

    List<Point> samplePoints = new ArrayList<Point>();

    long totalSampleSize = inTotalSize / 500;
    LOG.info("Going to pick a sample of size: "+totalSampleSize+" bytes");
    long totalBytesToSample = totalSampleSize;
    long lastTime = System.currentTimeMillis();

    FSDataInputStream[] inputStreams = new FSDataInputStream[inputPaths.length];
    for (int fileIndex = 0; fileIndex < inputPaths.length; fileIndex++) {
      inputStreams[fileIndex] = inFileSystem.open(inputPaths[fileIndex]);
    }
    
    Text text = new Text();
    while (totalBytesToSample > 0) {
      if (System.currentTimeMillis() - lastTime > 1000 * 60) {
        lastTime = System.currentTimeMillis();
        long sampledBytes = totalSampleSize - totalBytesToSample;
        LOG.info("Sampled " + (100 * sampledBytes / totalSampleSize) + "%");
      }

      long randomFilePosition = (Math.abs(random.nextLong()) % inTotalSize);
      int fileIndex = 0;
      while (randomFilePosition > inputSize[fileIndex]) {
        randomFilePosition -= inputSize[fileIndex];
        fileIndex++;
      }
      if (inputSize[fileIndex] - randomFilePosition < MaxLineLength) {
        randomFilePosition -= MaxLineLength;
      }
      
      inputStreams[fileIndex].seek(randomFilePosition);
      byte lastReadByte;
      do {
        lastReadByte = inputStreams[fileIndex].readByte();
      } while (lastReadByte != '\n' && lastReadByte != '\r');

      byte readLine[] = new byte[MaxLineLength];

      int readLineIndex = 0;

      while (inputStreams[fileIndex].getPos() < inputSize[fileIndex] - 1) {
        lastReadByte = inputStreams[fileIndex].readByte();
        if (lastReadByte == '\n' || lastReadByte == '\r') {
          break;
        }

        readLine[readLineIndex++] = lastReadByte;
      }
      // Skip an empty line
      if (readLineIndex < 4)
        continue;
      text.set(readLine, 0, readLineIndex);
      shape.fromText(text);

      samplePoints.add(shape.getMBR().getCenterPoint());
      totalBytesToSample -= readLineIndex;
    }
    
    for (int fileIndex = 0; fileIndex < inputPaths.length; fileIndex++) {
      inputStreams[fileIndex].close();
    }
    
    Point[] samples = samplePoints.toArray(new Point[samplePoints.size()]);
    LOG.info("Picked a sample of size: "+samples.length);
    return samples;
  }

}
