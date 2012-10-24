package edu.umn.cs.spatialHadoop.mapReduce;

import java.io.IOException;

import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.mapred.RecordWriter;
import org.apache.hadoop.mapred.Reporter;
import org.apache.hadoop.spatial.CellInfo;
import org.apache.hadoop.spatial.Shape;

public class RTreeGridRecordWriter<S extends Shape>
    extends org.apache.hadoop.spatial.RTreeGridRecordWriter<S>
    implements RecordWriter<CellInfo, S> {

  public RTreeGridRecordWriter(FileSystem fileSystem, Path outFile, CellInfo[] cells, boolean overwrite) throws IOException {
    super(fileSystem, outFile, cells, overwrite);
  }

  @Override
  public void close(Reporter reporter) throws IOException {
    super.close();
  }
}
