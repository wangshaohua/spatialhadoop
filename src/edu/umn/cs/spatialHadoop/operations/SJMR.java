package edu.umn.cs.spatialHadoop.operations;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.Vector;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FileStatus;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.io.IntWritable;
import org.apache.hadoop.io.LongWritable;
import org.apache.hadoop.io.Text;
import org.apache.hadoop.io.WritableComparable;
import org.apache.hadoop.mapred.ClusterStatus;
import org.apache.hadoop.mapred.FileInputFormat;
import org.apache.hadoop.mapred.FileSplit;
import org.apache.hadoop.mapred.InputSplit;
import org.apache.hadoop.mapred.JobClient;
import org.apache.hadoop.mapred.JobConf;
import org.apache.hadoop.mapred.MapReduceBase;
import org.apache.hadoop.mapred.Mapper;
import org.apache.hadoop.mapred.OutputCollector;
import org.apache.hadoop.mapred.RecordReader;
import org.apache.hadoop.mapred.Reducer;
import org.apache.hadoop.mapred.Reporter;
import org.apache.hadoop.mapred.TextOutputFormat;
import org.apache.hadoop.spatial.CellInfo;
import org.apache.hadoop.spatial.GridInfo;
import org.apache.hadoop.spatial.Rectangle;
import org.apache.hadoop.spatial.Shape;
import org.apache.hadoop.spatial.SpatialAlgorithms;
import org.apache.hadoop.util.LineReader;

import edu.umn.cs.CommandLineArguments;
import edu.umn.cs.spatialHadoop.TigerShape;
import edu.umn.cs.spatialHadoop.mapReduce.GridOutputFormat;
import edu.umn.cs.spatialHadoop.mapReduce.PairShape;
import edu.umn.cs.spatialHadoop.mapReduce.ShapeRecordReader;

/**
 * An implementation of Spatial Join MapReduce as it appears in
 * S. Zhang, J. Han, Z. Liu, K. Wang, and Z. Xu. SJMR:
 * Parallelizing spatial join with MapReduce on clusters. In
 * CLUSTER, pages 1–8, New Orleans, LA, Aug. 2009.
 * The map function partitions data into grid cells and the reduce function
 * makes a plane-sweep over each cell.
 * @author eldawy
 *
 */
public class SJMR {
  
  /**Class logger*/
  private static final Log LOG = LogFactory.getLog(SJMR.class);
  
  /**
   * The key output for the map function. Holds a shape with an index denoting
   * the file number it came from.
   * @author eldawy
   *
   * @param <T>
   */
  public static class IndexedShape<T extends Shape> implements WritableComparable<IndexedShape<T>> {
    /** Index of the shape */
    public int index;
    /** Value of the shape */
    public T shape;
    
    public IndexedShape() {
      shape = (T) new TigerShape();
    }

    public IndexedShape(int index, T shape) {
      this.index = index;
      this.shape = shape;
    }

    @Override
    public void write(DataOutput out) throws IOException {
      out.writeInt(index);
      shape.write(out);
    }

    @Override
    public void readFields(DataInput in) throws IOException {
      index = in.readInt();
      shape.readFields(in);
    }

    @Override
    public int compareTo(IndexedShape<T> o) {
      return index - o.index;
    }
  }
  
  /**
   * The map class maps each object to all cells it overlaps with.
   * @author eldawy
   *
   */
  public static class SJMRMap<S extends Shape> extends MapReduceBase
  implements
  Mapper<LongWritable, IndexedShape<S>, IntWritable, IndexedShape<S>> {
    /**List of cells used by the mapper*/
    private CellInfo[] cellInfos;
    private IntWritable cellId = new IntWritable();
    
    @Override
    public void configure(JobConf job) {
      String cellsInfoStr = job.get(GridOutputFormat.OUTPUT_CELLS);
      cellInfos = GridOutputFormat.decodeCells(cellsInfoStr);
      LOG.info(Arrays.asList(cellInfos));
      super.configure(job);
    }

    @Override
    public void map(LongWritable key, IndexedShape<S> value,
        OutputCollector<IntWritable, IndexedShape<S>> output,
        Reporter reporter) throws IOException {
      for (int cellIndex = 0; cellIndex < cellInfos.length; cellIndex++) {
        if (cellInfos[cellIndex].isIntersected(value.shape)) {
          cellId.set((int)cellInfos[cellIndex].cellId);
          output.collect(cellId, value);
        }
      }
    }
  }
  
  public static class SJMRReduce<S extends Shape> extends MapReduceBase implements
  Reducer<IntWritable, IndexedShape<S>, CellInfo, PairShape<S>> {
    /**Number of files in the input*/
    private int inputFileCount;
    
    /**List of cells used by the reducer*/
    private CellInfo[] cellInfos;
    
    @Override
    public void configure(JobConf job) {
      super.configure(job);
      String cellsInfoStr = job.get(GridOutputFormat.OUTPUT_CELLS);
      cellInfos = GridOutputFormat.decodeCells(cellsInfoStr);
      
      inputFileCount = FileInputFormat.getInputPaths(job).length;
    }

    @Override
    public void reduce(IntWritable cellId, Iterator<IndexedShape<S>> values,
        final OutputCollector<CellInfo, PairShape<S>> output, Reporter reporter)
        throws IOException {
      int i_cell = 0;
      while (i_cell < cellInfos.length && cellInfos[i_cell].cellId != cellId.get())
        i_cell++;
      final CellInfo cellInfo = cellInfos[i_cell];
      
      // Partition retrieved shapes (values) into lists for each file
      @SuppressWarnings("unchecked")
      List<S>[] shapeLists = new List[inputFileCount];
      for (int i = 0; i < shapeLists.length; i++) {
        shapeLists[i] = new Vector<S>();
      }
      
      while (values.hasNext()) {
        IndexedShape<S> s = values.next();
        shapeLists[s.index].add((S) s.shape.clone());
      }
      
      final PairShape<S> value = new PairShape<S>();
      // Perform spatial join between the two lists
      SpatialAlgorithms.SpatialJoin_planeSweep(shapeLists[0], shapeLists[1], new SpatialAlgorithms.ResultCollector2<S, S>() {
        @Override
        public void add(S x, S y) {
          try {
            // Report to the reduce result collector
            value.first = x;
            value.second = y;
            output.collect(cellInfo, value);
          } catch (IOException e) {
            e.printStackTrace();
          }
        }
      });
    }
    
  }
  
  /**A record reader that annotates shapes with the file index it came from*/
  public static class SJMRRecordReader<S extends Shape>
      implements RecordReader<LongWritable, IndexedShape<S>> {
    
    /**The internal reader that reads shape*/
    private ShapeRecordReader<S> internalReader;
    
    /**The index of the file being read*/
    private int index;
    
    public SJMRRecordReader(Configuration job, FileSplit split, int fileIndex)
        throws IOException {
      internalReader = new ShapeRecordReader<S>(job, split);
      this.index = fileIndex;
    }
    
    @Override
    public boolean next(LongWritable key, IndexedShape<S> value)
        throws IOException {
      return internalReader.next(key, value.shape);
    }

    @Override
    public LongWritable createKey() {
      return internalReader.createKey();
    }

    @Override
    public IndexedShape<S> createValue() {
      LOG.info("Creating a value");
      return new IndexedShape<S>(index, internalReader.createValue());
    }

    @Override
    public long getPos() throws IOException {
      return internalReader.getPos();
    }

    @Override
    public void close() throws IOException {
      internalReader.close();
    }

    @Override
    public float getProgress() throws IOException {
      return internalReader.getProgress();
    }
  }
  
  /**
   * An input format that returns the customized record reader.
   * @author eldawy
   *
   * @param <S>
   */
  public static class SJMRInputFormat<S extends Shape>
      extends FileInputFormat<LongWritable, IndexedShape<S>> {
    @Override
    public RecordReader<LongWritable, IndexedShape<S>> getRecordReader(
        InputSplit split, JobConf job, Reporter reporter) throws IOException {
      FileSplit fsplit = (FileSplit) split;
      Path[] dirs = getInputPaths(job);
      for (int i = 0; i < dirs.length; i++) {
        if (dirs[i].equals(fsplit.getPath())) {
          return new SJMRRecordReader<S>(job, (FileSplit) split, i);
        }
      }
      LOG.error("Cannot find the index of the split: "+split);
      return null;
    }
  }

  public static<S extends Shape> long sjmr(FileSystem fs, Path[] files,
      GridInfo gridInfo, OutputCollector<PairShape<CellInfo>, PairShape<? extends Shape>> output)
          throws IOException {
    JobConf job = new JobConf(SJMR.class);
    
    Path outputPath;
    FileSystem outFs = files[0].getFileSystem(job);
    do {
      outputPath = new Path(files[0].toUri().getPath()+
          ".sjmr_"+(int)(Math.random() * 1000000));
    } while (outFs.exists(outputPath));
    outFs.deleteOnExit(outputPath);
    
    job.setJobName("SJMR");
    job.setMapperClass(SJMRMap.class);
    job.setMapOutputKeyClass(IntWritable.class);
    job.setMapOutputValueClass(IndexedShape.class);

    job.setReducerClass(SJMRReduce.class);
    ClusterStatus clusterStatus = new JobClient(job).getClusterStatus();
    job.setNumReduceTasks(Math.max(1, clusterStatus.getMaxReduceTasks()));

    job.setInputFormat(SJMRInputFormat.class);
    job.set(ShapeRecordReader.SHAPE_CLASS, TigerShape.class.getName());
    job.setOutputFormat(TextOutputFormat.class);
    
    String commaSeparatedFiles = "";
    for (int i = 0; i < files.length; i++) {
      if (i > 0)
        commaSeparatedFiles += ',';
      commaSeparatedFiles += files[i].toUri().toString();
    }
    SJMRInputFormat.addInputPaths(job, commaSeparatedFiles);
    
    // Calculate and set the dimensions of the grid to use in the map phase
    long total_size = 0;
    for (Path file : files) {
      total_size += fs.getFileStatus(file).getLen();
    }
    total_size += total_size * job.getFloat(Repartition.REPLICATION_OVERHEAD,
        0.002f);
    int num_cells = (int) (total_size / outFs.getDefaultBlockSize());
    gridInfo.calculateCellDimensions(num_cells);
    CellInfo[] cellsInfo = gridInfo.getAllCells();
    job.set(GridOutputFormat.OUTPUT_CELLS,
        GridOutputFormat.encodeCells(cellsInfo));
    
    TextOutputFormat.setOutputPath(job, outputPath);
    
    // Start the job
    JobClient.runJob(job);

    // Read job result
    FileStatus[] results = outFs.listStatus(outputPath);
    long resultCount = 0;
    for (FileStatus fileStatus : results) {
      if (fileStatus.getLen() > 0 && fileStatus.getPath().getName().startsWith("part-")) {
        resultCount += RecordCount.recordCountLocal(outFs, fileStatus.getPath());
        if (output != null) {
          // Report every single result as a pair of shapes
          PairShape<CellInfo> cells =
              new PairShape<CellInfo>(new CellInfo(), new CellInfo());
          PairShape<? extends Shape> shapes =
              new PairShape<TigerShape>(new TigerShape(), new TigerShape());
          LineReader lineReader = new LineReader(outFs.open(fileStatus.getPath()));
          Text text = new Text();
          if (lineReader.readLine(text) > 0) {
            String str = text.toString();
            String[] parts = str.split("\t", 2);
            cells.readFromString(parts[0]);
            shapes.readFromString(parts[1]);
            output.collect(cells, shapes);
          }
          lineReader.close();
        }
      }
    }
    
    return resultCount;
  }
  
  /**
   * @param args
   * @throws IOException 
   */
  public static void main(String[] args) throws IOException {
    CommandLineArguments cla = new CommandLineArguments(args);
    Path[] inputPaths = cla.getPaths();
    JobConf conf = new JobConf(RedistributeJoin.class);
    GridInfo gridInfo = cla.getGridInfo();
    if (gridInfo == null) {
      Rectangle rect = cla.getRectangle();
      if (rect != null) {
        gridInfo = new GridInfo(rect.x, rect.y, rect.width, rect.height);
      }
    }
    FileSystem fs = inputPaths[0].getFileSystem(conf);
    long t1 = System.currentTimeMillis();
    long resultSize = sjmr(fs, inputPaths, gridInfo, null);
    long t2 = System.currentTimeMillis();
    System.out.println("Total time: "+(t2-t1)+" millis");
    System.out.println("Result size: "+resultSize);
  }

}
