/*  
 *   Copyright 2012 OSBI Ltd
 *
 *   Licensed under the Apache License, Version 2.0 (the "License");
 *   you may not use this file except in compliance with the License.
 *   You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 *   Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *   See the License for the specific language governing permissions and
 *   limitations under the License.
 */
package org.saiku.olap.dto.resultset;

import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class Matrix {

  private final Map<List<Integer>, AbstractBaseCell> map = new HashMap<>();

  private int width = 0;

  private int height = 0;

  private int offset = 0;

  private final Set<Integer> xAxis = new HashSet<>();
  private final Set<Integer> yAxis = new HashSet<>();


  public Matrix() {
  }

  /**
   * Creats a Matrix.
   *
   * @param width  Width of matrix
   * @param height Height of matrix
   */
  public Matrix( final int width, final int height ) {
    this.width = width;
    this.height = height;
  }

  /**
   * Sets the value at a particular coordinate
   *
   * @param x          X coordinate
   * @param y          Y coordinate
   */
  public void set( final int x, final int y, final DataCell cell ) {
    map.put( Arrays.asList( x, y ), cell );
    addCoordinates( x, y );
    assert x >= 0 && x < width : x;
    assert y >= 0 && y < height : y;
  }

  /**
   * Sets the value at a particular coordinate
   *
   * @param x          - X coordinate
   * @param y          - Y coordinate
   * @param value      - Value
   */
  public void set( final int x, final int y, final MemberCell value ) {
    map.put( Arrays.asList( x, y ), value );
    addCoordinates( x, y );
    assert x >= 0 && x < width : x;
    assert y >= 0 && y < height : y;
  }

  /**
   * Returns the cell at a particular coordinate.
   *
   * @param x X coordinate
   * @param y Y coordinate
   * @return Cell
   */
  public AbstractBaseCell get( final int x, final int y ) {
    return map.get( Arrays.asList( x, y ) );
  }

  /**
   * Return the width of the created matrix.
   *
   * @return the width
   */
  public int getMatrixWidth() {
    //        return width;
    return xAxis.size();
  }

  /**
   * Return the height of the matrix.
   *
   * @return the height
   */
  public int getMatrixHeight() {
    //        return height;
    return yAxis.size();
  }

  /**
   * Return the generated hashmap.
   *
   * @return the map
   */
  public Map<List<Integer>, AbstractBaseCell> getMap() {
    return map;
  }

  /**
   * Set the header/row data offset.
   *
   * @param offset
   */
  public void setOffset( final int offset ) {
    this.offset = offset;
  }

  /**
   * Return the header/row data offset.
   *
   * @return offset
   */
  public int getOffset() {
    return offset;

  }

  private void addCoordinates( Integer x, Integer y ) {
    xAxis.add( x );
    yAxis.add( y );
  }

  public boolean containsY( Integer yCoordinate ) {
    return yAxis.contains( yCoordinate );
  }

  public boolean containsX( Integer xCoordinate ) {
    return xAxis.contains( xCoordinate );
  }

}
