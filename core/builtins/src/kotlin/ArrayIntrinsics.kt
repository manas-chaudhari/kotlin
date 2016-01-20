/*
 * Copyright 2010-2015 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package kotlin

/**
 * Returns an array with the specified [size], where each element is calculated by calling the specified
 * [init] function. The `init` function returns an array element given its index.
 */
public inline fun <reified T> Array(size: Int, init: (Int) -> T): Array<T> {
    val result = arrayOfNulls<T>(size)
    for (i in 0..size - 1)
        result[i] = init(i)
    return result as Array<T>
}

/**
 * Returns an empty array of the specified type [T].
 */
public inline fun <reified T> emptyArray(): Array<T> = arrayOfNulls<T>(0) as Array<T>


// Array "constructor"
/**
 * Returns an array containing the specified elements.
 */
public inline fun <reified T> arrayOf(vararg elements: T) : Array<T> = elements as Array<T>

// "constructors" for primitive types array
/**
 * Returns an array containing the specified [Double] numbers.
 */
public fun doubleArrayOf(vararg elements: Double) : DoubleArray    = elements

/**
 * Returns an array containing the specified [Float] numbers.
 */
public fun floatArrayOf(vararg elements: Float) : FloatArray       = elements

/**
 * Returns an array containing the specified [Long] numbers.
 */
public fun longArrayOf(vararg elements: Long) : LongArray          = elements

/**
 * Returns an array containing the specified [Int] numbers.
 */
public fun intArrayOf(vararg elements: Int) : IntArray             = elements

/**
 * Returns an array containing the specified characters.
 */
public fun charArrayOf(vararg elements: Char) : CharArray          = elements

/**
 * Returns an array containing the specified [Short] numbers.
 */
public fun shortArrayOf(vararg elements: Short) : ShortArray       = elements

/**
 * Returns an array containing the specified [Byte] numbers.
 */
public fun byteArrayOf(vararg elements: Byte) : ByteArray          = elements

/**
 * Returns an array containing the specified boolean values.
 */
public fun booleanArrayOf(vararg elements: Boolean) : BooleanArray = elements
