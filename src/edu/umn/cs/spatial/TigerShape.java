package edu.umn.cs.spatial;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;

import org.apache.hadoop.spatial.Point;
import org.apache.hadoop.spatial.Rectangle;
import org.apache.hadoop.spatial.Shape;

/**
 * A shape from tiger file.
 * This is a wrapper to any shape and delegate all method to shape.
 * @author aseldawy
 *
 */
public class TigerShape implements Shape {
  public Shape shape;
  public long id;

  public TigerShape() {
    
  }
  
  public TigerShape(Shape shape, long id) {
    this.shape = shape;
    this.id = id;
  }

  public TigerShape(TigerShape tigerShape) {
    this.id = tigerShape.id;
    this.shape = tigerShape.shape;
  }

  public Rectangle getMBR() {
    return shape.getMBR();
  }

  public Point getCenterPoint() {
    return shape.getCenterPoint();
  }

  public Point getCentroid() {
    return shape.getCentroid();
  }

  public double getMinDistanceTo(Shape s) {
    return shape.getMinDistanceTo(s);
  }

  public double getMaxDistanceTo(Shape s) {
    return shape.getMaxDistanceTo(s);
  }

  public double getAvgDistanceTo(Shape s) {
    return shape.getAvgDistanceTo(s);
  }

  public Shape getIntersection(Shape s) {
    return shape.getIntersection(s);
  }

  public boolean isIntersected(Shape s) {
    return shape.isIntersected(s);
  }

  public Shape union(Shape s) {
    return shape.union(s);
  }

  @Override
  public void write(DataOutput out) throws IOException {
    out.writeLong(id);
    out.writeUTF(shape.getClass().getName());
    shape.write(out);
  }

  @Override
  public void readFields(DataInput in) throws IOException {
    id = in.readLong();
    String shapeClassName = in.readUTF();
    try {
      if (shape == null || !shape.getClass().getName().equals(shapeClassName)) {
        // Create a shape of the new class
        Class<Shape> shapeClass = (Class<Shape>) Class.forName(shapeClassName);
        shape = shapeClass.newInstance();
      }
      shape.readFields(in);
    } catch (ClassNotFoundException e) {
      shape = null;
    } catch (InstantiationException e) {
      shape = null;
    } catch (IllegalAccessException e) {
      shape = null;
    }
  }

  @Override
  public int compareTo(Shape s) {
    Shape shape = s instanceof TigerShape? ((TigerShape)s).shape : s;
    return shape.compareTo(shape);
  }

  @Override
  public Object clone() {
    return new TigerShape((Shape) shape.clone(), id);
  }
  
  @Override
  public String toString() {
    return "TIGER #"+id+" "+shape;
  }

  @Override
  public String writeToString() {
    return id+","+shape.writeToString();
  }

  @Override
  public void readFromString(String string) {
    int i = string.indexOf(',');
    this.id = Long.parseLong(string.substring(0, i));
    shape.readFromString(string.substring(i+1));
  }
}
