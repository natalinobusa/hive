/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.hadoop.hive.ql.exec;

import java.util.Arrays;
import org.apache.hadoop.hive.ql.exec.vector.DoubleColumnVector;
import org.apache.hadoop.hive.ql.exec.vector.LongColumnVector;
import org.apache.hadoop.hive.ql.exec.vector.VectorizedRowBatch;
import org.apache.hadoop.hive.ql.exec.vector.expressions.VectorExpression;
import org.apache.hadoop.hive.ql.metadata.HiveException;
import org.apache.hadoop.hive.serde2.io.DoubleWritable;
import org.apache.hadoop.io.LongWritable;

/**
 * Class for handling vectorized hash map key wrappers. It evaluates the key columns in a 
 * row batch in a vectorized fashion.
 * This class stores additional information about keys needed to evaluate and output the key values.
 *
 */
public class VectorHashKeyWrapperBatch {
  
  /**
   * Helper class for looking up a key value based on key index
   *
   */
  private static class KeyLookupHelper {
    public int longIndex;
    public int doubleIndex;
  }
  
  /**
   * The key expressions that require evaluation and output the primitive values for each key.
   */
  private VectorExpression[] keyExpressions;
  
  /**
   * indices of LONG primitive keys
   */
  private int[] longIndices;
  
  /**
   * indices of DOUBLE primitive keys
   */
  private int[] doubleIndices;
  
  /**
   * pre-allocated batch size vector of keys wrappers. 
   * N.B. these keys are **mutable** and should never be used in a HashMap.
   * Always clone the key wrapper to obtain an immutable keywrapper suitable 
   * to use a key in a HashMap.
   */
  private VectorHashKeyWrapper[] vectorHashKeyWrappers;
  
  /**
   * lookup vector to map from key index to primitive type index
   */
  private KeyLookupHelper[] indexLookup;
  
  /**
   * preallocated and reused LongWritable objects for emiting row mode key values 
   */
  private LongWritable[] longKeyValueOutput;
  
  /**
   * preallocated and reused DoubleWritable objects for emiting row mode key values
   */
  private DoubleWritable[] doubleKeyValueOutput;
  
  /**
   * Accessor for the batch-sized array of key wrappers 
   */
  public VectorHashKeyWrapper[] getVectorHashKeyWrappers() {
    return vectorHashKeyWrappers;
  }
  
  /**
   * Processes a batch:
   * <ul>
   * <li>Evaluates each key vector expression.</li>
   * <li>Copies out each key's primitive values into the key wrappers</li>
   * <li>computes the hashcode of the key wrappers</li>
   * </ul>
   * @param vrb
   * @throws HiveException
   */
  public void evaluateBatch (VectorizedRowBatch vrb) throws HiveException {
    for(int i = 0; i < keyExpressions.length; ++i) {
      keyExpressions[i].evaluate(vrb);
    }
    for(int i = 0; i< longIndices.length; ++i) {
      int keyIndex = longIndices[i];
      int columnIndex = keyExpressions[keyIndex].getOutputColumn();
      LongColumnVector columnVector = (LongColumnVector) vrb.cols[columnIndex];
      if (columnVector.noNulls && !columnVector.isRepeating && !vrb.selectedInUse) {
        assignLongNoNullsNoRepeatingNoSelection(i, vrb.size, columnVector);
      } else if (columnVector.noNulls && !columnVector.isRepeating && vrb.selectedInUse) {
        assignLongNoNullsNoRepeatingSelection(i, vrb.size, columnVector, vrb.selected);
      } else if (columnVector.noNulls && columnVector.isRepeating) {
        assignLongNoNullsRepeating(i, vrb.size, columnVector);
      } else if (!columnVector.noNulls && !columnVector.isRepeating && !vrb.selectedInUse) {
        assignLongNullsNoRepeatingNoSelection(i, vrb.size, columnVector);
      } else if (!columnVector.noNulls && columnVector.isRepeating) {
        assignLongNullsRepeating(i, vrb.size, columnVector);
      } else if (!columnVector.noNulls && !columnVector.isRepeating && vrb.selectedInUse) {
        assignLongNullsNoRepeatingSelection (i, vrb.size, columnVector, vrb.selected);
      } else {
        throw new HiveException (String.format("Unimplemented Long null/repeat/selected combination %b/%b/%b",
            columnVector.noNulls, columnVector.isRepeating, vrb.selectedInUse));
      }
    }
    for(int i=0;i<doubleIndices.length; ++i) {
      int keyIndex = doubleIndices[i];
      int columnIndex = keyExpressions[keyIndex].getOutputColumn();
      DoubleColumnVector columnVector = (DoubleColumnVector) vrb.cols[columnIndex];
      if (columnVector.noNulls && !columnVector.isRepeating && !vrb.selectedInUse) {
        assignDoubleNoNullsNoRepeatingNoSelection(i, vrb.size, columnVector);
      } else if (columnVector.noNulls && !columnVector.isRepeating && vrb.selectedInUse) {
        assignDoubleNoNullsNoRepeatingSelection(i, vrb.size, columnVector, vrb.selected);
      } else if (columnVector.noNulls && columnVector.isRepeating) {
        assignDoubleNoNullsRepeating(i, vrb.size, columnVector);
      } else if (!columnVector.noNulls && !columnVector.isRepeating && !vrb.selectedInUse) {
        assignDoubleNullsNoRepeatingNoSelection(i, vrb.size, columnVector);
      } else if (!columnVector.noNulls && columnVector.isRepeating) {
        assignDoubleNullsRepeating(i, vrb.size, columnVector);
      } else if (!columnVector.noNulls && !columnVector.isRepeating && vrb.selectedInUse) {
        assignDoubleNullsNoRepeatingSelection (i, vrb.size, columnVector, vrb.selected);
      } else {
        throw new HiveException (String.format("Unimplemented Double null/repeat/selected combination %b/%b/%b",
            columnVector.noNulls, columnVector.isRepeating, vrb.selectedInUse));
      }
    }
    for(int i=0;i<vrb.size;++i) {
      vectorHashKeyWrappers[i].setHashKey();
    }
  }
  
  /**
   * Helper method to assign values from a vector column into the key wrapper.
   * Optimized for double type, possible nulls, no repeat values, batch selection vector.
   */
  private void assignDoubleNullsNoRepeatingSelection(int index, int size,
      DoubleColumnVector columnVector, int[] selected) {
    for(int r = 0; r < size; ++r) {
      if (!columnVector.isNull[r]) {
        vectorHashKeyWrappers[r].assignDouble(index, columnVector.vector[selected[r]]);
      } else {
        vectorHashKeyWrappers[r].assignNullDouble(index);
      }
    }
  }

  /**
   * Helper method to assign values from a vector column into the key wrapper.
   * Optimized for Double type, repeat null values.
   */
  private void assignDoubleNullsRepeating(int index, int size,
      DoubleColumnVector columnVector) {
    for(int r = 0; r < size; ++r) {
      vectorHashKeyWrappers[r].assignNullDouble(index);
    }    
  }

  /**
   * Helper method to assign values from a vector column into the key wrapper.
   * Optimized for Double type, possible nulls, repeat values.
   */
  private void assignDoubleNullsNoRepeatingNoSelection(int index, int size,
      DoubleColumnVector columnVector) {
    for(int r = 0; r < size; ++r) {
      if (!columnVector.isNull[r]) {
        vectorHashKeyWrappers[r].assignDouble(index, columnVector.vector[r]);
      } else {
        vectorHashKeyWrappers[r].assignNullDouble(index);
      }
    }    
  }

  /**
   * Helper method to assign values from a vector column into the key wrapper.
   * Optimized for double type, no nulls, repeat values, no selection vector.
   */
  private void assignDoubleNoNullsRepeating(int index, int size, DoubleColumnVector columnVector) {
    for(int r = 0; r < size; ++r) {
      vectorHashKeyWrappers[r].assignDouble(index, columnVector.vector[0]);
    }
  }

  /**
   * Helper method to assign values from a vector column into the key wrapper.
   * Optimized for double type, no nulls, no repeat values, batch selection vector.
   */
  private void assignDoubleNoNullsNoRepeatingSelection(int index, int size,
      DoubleColumnVector columnVector, int[] selected) {
    for(int r = 0; r < size; ++r) {
      vectorHashKeyWrappers[r].assignDouble(index, columnVector.vector[selected[r]]);
    }
  }

  /**
   * Helper method to assign values from a vector column into the key wrapper.
   * Optimized for double type, no nulls, no repeat values, no selection vector.
   */
  private void assignDoubleNoNullsNoRepeatingNoSelection(int index, int size,
      DoubleColumnVector columnVector) {
    for(int r = 0; r < size; ++r) {
      vectorHashKeyWrappers[r].assignDouble(index, columnVector.vector[r]);
    }
  }
  
  /**
   * Helper method to assign values from a vector column into the key wrapper.
   * Optimized for double type, possible nulls, no repeat values, batch selection vector.
   */
  private void assignLongNullsNoRepeatingSelection(int index, int size,
      LongColumnVector columnVector, int[] selected) {
    for(int r = 0; r < size; ++r) {
      if (!columnVector.isNull[selected[r]]) {
        vectorHashKeyWrappers[r].assignLong(index, columnVector.vector[selected[r]]);
      } else {
        vectorHashKeyWrappers[r].assignNullLong(index);
      }
    }
  }

  /**
   * Helper method to assign values from a vector column into the key wrapper.
   * Optimized for double type, repeating nulls.
   */
  private void assignLongNullsRepeating(int index, int size,
      LongColumnVector columnVector) {
    for(int r = 0; r < size; ++r) {
      vectorHashKeyWrappers[r].assignNullLong(index);
    }
  }

  /**
   * Helper method to assign values from a vector column into the key wrapper.
   * Optimized for double type, possible nulls, no repeat values, no selection vector.
   */
  private void assignLongNullsNoRepeatingNoSelection(int index, int size,
      LongColumnVector columnVector) {
    for(int r = 0; r < size; ++r) {
      if (!columnVector.isNull[r]) {
        vectorHashKeyWrappers[r].assignLong(index, columnVector.vector[r]);
      } else {
        vectorHashKeyWrappers[r].assignNullLong(index);
      }
    }    
  }

  /**
   * Helper method to assign values from a vector column into the key wrapper.
   * Optimized for double type, no nulls, repeat values, no selection vector.
   */
  private void assignLongNoNullsRepeating(int index, int size, LongColumnVector columnVector) {
    for(int r = 0; r < size; ++r) {
      vectorHashKeyWrappers[r].assignLong(index, columnVector.vector[0]);
    }
  }

  /**
   * Helper method to assign values from a vector column into the key wrapper.
   * Optimized for double type, no nulls, no repeat values, batch selection vector.
   */
  private void assignLongNoNullsNoRepeatingSelection(int index, int size,
      LongColumnVector columnVector, int[] selected) {
    for(int r = 0; r < size; ++r) {
      vectorHashKeyWrappers[r].assignLong(index, columnVector.vector[selected[r]]);
    }
  }

  /**
   * Helper method to assign values from a vector column into the key wrapper.
   * Optimized for double type, no nulls, no repeat values, no selection vector.
   */
  private void assignLongNoNullsNoRepeatingNoSelection(int index, int size,
      LongColumnVector columnVector) {
    for(int r = 0; r < size; ++r) {
      vectorHashKeyWrappers[r].assignLong(index, columnVector.vector[r]);
    }
  }

  /**
   * Prepares a VectorHashKeyWrapperBatch to work for a specific set of keys.
   * Computes the fast access lookup indices, preallocates all needed internal arrays.
   * This step is done only once per query, not once per batch. The information computed now
   * will be used to generate proper individual VectorKeyHashWrapper objects.
   */
  public static VectorHashKeyWrapperBatch compileKeyWrapperBatch(VectorExpression[] keyExpressions)
    throws HiveException {
    VectorHashKeyWrapperBatch compiledKeyWrapperBatch = new VectorHashKeyWrapperBatch();
    compiledKeyWrapperBatch.keyExpressions = keyExpressions;
    
    // We'll overallocate and then shrink the array for each type
    int[] longIndices = new int[keyExpressions.length];
    int longIndicesIndex = 0;
    int[] doubleIndices = new int[keyExpressions.length];
    int doubleIndicesIndex  = 0;
    KeyLookupHelper[] indexLookup = new KeyLookupHelper[keyExpressions.length];
    
    // Inspect the output type of each key expression.
    for(int i=0; i < keyExpressions.length; ++i) {
      indexLookup[i] = new KeyLookupHelper();
      String outputType = keyExpressions[i].getOutputType();
      if (outputType.equalsIgnoreCase("long") || 
          outputType.equalsIgnoreCase("bigint")) {
        longIndices[longIndicesIndex] = i;
        indexLookup[i].longIndex = longIndicesIndex;
        indexLookup[i].doubleIndex = -1;
        ++longIndicesIndex;
      } else if (outputType.equalsIgnoreCase("double")) {
        doubleIndices[doubleIndicesIndex] = i;
        indexLookup[i].longIndex = -1;
        indexLookup[i].doubleIndex = doubleIndicesIndex;
        ++doubleIndicesIndex;
      } else {
        throw new HiveException("Unsuported vector output type: " + outputType);
      }
    }
    compiledKeyWrapperBatch.indexLookup = indexLookup;
    compiledKeyWrapperBatch.longKeyValueOutput = new LongWritable[longIndicesIndex];
    for (int i=0; i < longIndicesIndex; ++i) {
      compiledKeyWrapperBatch.longKeyValueOutput[i] = new LongWritable();
    }
    compiledKeyWrapperBatch.doubleKeyValueOutput = new DoubleWritable[doubleIndicesIndex];
    for (int i=0; i < doubleIndicesIndex; ++i) {
      compiledKeyWrapperBatch.doubleKeyValueOutput[i] = new DoubleWritable();
    }
    compiledKeyWrapperBatch.longIndices = Arrays.copyOf(longIndices, longIndicesIndex);
    compiledKeyWrapperBatch.doubleIndices = Arrays.copyOf(doubleIndices, doubleIndicesIndex);
    compiledKeyWrapperBatch.vectorHashKeyWrappers = 
        new VectorHashKeyWrapper[VectorizedRowBatch.DEFAULT_SIZE];
    for(int i=0;i<VectorizedRowBatch.DEFAULT_SIZE; ++i) {
      compiledKeyWrapperBatch.vectorHashKeyWrappers[i] = 
          new VectorHashKeyWrapper(longIndicesIndex, doubleIndicesIndex);
    }
    return compiledKeyWrapperBatch;
  }

  /**
   * Get the row-mode writable object value of a key from a key wrapper
   */
  public Object getWritableKeyValue(VectorHashKeyWrapper kw, int i) 
    throws HiveException {
    if (kw.getIsNull(i)) {
      return null;
    }
    KeyLookupHelper klh = indexLookup[i];
    if (klh.longIndex >= 0) {
      longKeyValueOutput[klh.longIndex].set(kw.getLongValue(i));
      return longKeyValueOutput[klh.longIndex];
    } else if (klh.doubleIndex >= 0) {
      doubleKeyValueOutput[klh.doubleIndex].set(kw.getDoubleValue(i));
      return doubleKeyValueOutput[klh.doubleIndex];
    } else {
      throw new HiveException(String.format(
          "Internal inconsistent KeyLookupHelper at index [%d]:%d %d",
          i, klh.longIndex, klh.doubleIndex));
    }
  }  
}
