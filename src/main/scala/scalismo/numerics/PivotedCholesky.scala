/*
 * Copyright 2015 University of Basel, Graphics and Vision Research Group
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package scalismo.numerics

import breeze.linalg.svd.SVD
import breeze.linalg.{ DenseMatrix, DenseVector, sum }
import breeze.numerics.pow
import scalismo.geometry._
import scalismo.kernels.{ MatrixValuedPDKernel, PDKernel }
import scalismo.utils.Benchmark

import scala.collection.mutable.ArrayBuffer
import scala.collection.parallel.mutable.ParArray

/**
 * Result object for the pivoted cholesky of a matrix A
 * @param L The (first m columns) of a lower triangular matrix L, for which LL' = A_m \approx A.
 * @param p The pivot
 * @param tr : The trace of the matrix (A_m - A) (i.e. the approximation error)
 */
case class PivotedCholesky(L: DenseMatrix[Double], p: IndexedSeq[Int], tr: Double)

private class PivotedCholeskyFactor(d: Int, sizeHint: Int = 20) {
  val cols = new ArrayBuffer[DenseVector[Double]](sizeHint)

  def apply(row: Int, col: Int): Double = cols(col)(row)

  def col(i: Int): DenseVector[Double] = cols(i)

  def addCol(vec: DenseVector[Double]): Unit = {
    require(vec.length == d)
    cols += vec
  }

  def setCol(col: Int, vec: DenseVector[Double]): Unit = {
    require(col >= 0 && col < cols.size + 1)
    require(vec.length == d)
    if (col < cols.size)
      cols(col) = vec
    else
      cols += vec
  }

  def toDenseMatrix: DenseMatrix[Double] = {
    val m = DenseMatrix.zeros[Double](d, cols.size)
    for (i <- cols.indices) {
      m(::, i) := cols(i)
    }
    m
  }
}

object PivotedCholesky {

  sealed trait StoppingCriterion
  case class AbsoluteTolerance(tol: Double) extends StoppingCriterion
  case class RelativeTolerance(tol: Double) extends StoppingCriterion
  case class NumberOfEigenfunctions(n: Int) extends StoppingCriterion

  private[this] def computeApproximateCholeskyGeneric[A](kernel: (A, A) => Double,
    xs: IndexedSeq[A],
    stoppingCriterion: StoppingCriterion): PivotedCholesky = {

    val n = xs.size

    val p = scala.collection.mutable.IndexedSeq.range(0, n)

    val diagData = for (x <- xs) yield kernel(x, x)
    val d = DenseVector[Double](diagData.toArray)

    var tr: Double = breeze.linalg.sum(d)
    var k = 0

    val (tolerance, maxNumEigenfunctions) = stoppingCriterion match {
      case AbsoluteTolerance(t) => (t, n)
      case RelativeTolerance(t) => (tr * t, n)
      case NumberOfEigenfunctions(numEigenfuns) => (1e-15, numEigenfuns)
    }

    // The matrix will hold the result (i.e. LL' is the resulting kernel matrix). As we do not know the
    // number of columns we compute until we have the desired accuracy, the matrix is updated in each iteration.
    val L = new PivotedCholeskyFactor(n, n)

    // we either loop until we have the required number of eigenfunction, the precition or
    // the trace is not decreasing anymore (which is a sign of numerical instabilities)
    while (k < n && k < maxNumEigenfunctions && tr >= tolerance) {

      val S = DenseVector.zeros[Double](n)
      // get biggest element for pivot and switch
      val pivl = /*k + */ (k until n).map(i => (i, d(p(i)))).maxBy(_._2)._1

      val tmp = p(k)
      p(k) = p(pivl)
      p(pivl) = tmp

      S(p(k)) = Math.sqrt(d(p(k)))

      var rhs = DenseVector.zeros[Double](n - k - 1)

      var i = 0

      var r = k + 1

      while (r < n) {

        var sum = 0.0
        var c = 0
        while (c < k) {
          sum += L(p(r), c) * L(p(k), c)
          c += 1
        }

        S(p(r)) = (kernel(xs(p(r)), xs(p(k))) - sum) / S(p(k))

        i += 1
        r += 1

      }

      val lll = S(p.slice(k, n)).map(e => e * e)
      val ddd = d(p.slice(k, n)).toDenseVector
      d(p.slice(k, n)) := ddd - lll
      tr = sum(d(p.slice(k, n)))
      L.addCol(S.toDenseVector)

      println(s"Iteration: $k | Trace: $tr")

      k += 1
    }

    PivotedCholesky(L.toDenseMatrix, p, tr)

  }

  def computeApproximateCholesky[D <: Dim: NDSpace, DO <: Dim: NDSpace](kernel: MatrixValuedPDKernel[D, DO],
    xs: IndexedSeq[Point[D]],
    stoppingCriterion: StoppingCriterion): PivotedCholesky = {

    case class PointWithDim(point: Point[D], dim: Int)
    val dim = NDSpace[DO].dimensionality
    val xsWithDim: IndexedSeq[PointWithDim] = xs.flatMap(f => (0 until dim).map(i => PointWithDim(f, i)))
    def kscalar(x: PointWithDim, y: PointWithDim): Double = kernel(x.point, y.point)(x.dim, y.dim)

    computeApproximateCholeskyGeneric[PointWithDim](kscalar, xsWithDim, stoppingCriterion)

  }

  def computeApproximateCholesky[D <: Dim: NDSpace](kernel: PDKernel[D],
    xs: IndexedSeq[Point[D]],
    stoppingCriterion: StoppingCriterion): PivotedCholesky = {
    val k: (Point[D], Point[D]) => Double = (x, y) => kernel(x, y)
    computeApproximateCholeskyGeneric[Point[D]](k, xs, stoppingCriterion)
  }

  def computeApproximateCholesky(A: DenseMatrix[Double], stoppingCriterion: StoppingCriterion): PivotedCholesky = {

    require(A.cols == A.rows)
    val kernel: (Int, Int) => Double = (i, j) => A(i, j)
    val indices = IndexedSeq.range(0, A.cols)
    computeApproximateCholeskyGeneric[Int](kernel, indices, stoppingCriterion)
  }

  private def computeApproximateEigGeneric[A](k: (A, A) => Double, xs: IndexedSeq[A], D: Double, sc: StoppingCriterion) = {

    val PivotedCholesky(l, _, _) = computeApproximateCholeskyGeneric(k, xs, sc)

    val LD = l(::, 0 until l.cols).t :* D
    val phi: DenseMatrix[Double] = LD * l(::, 0 until l.cols)

    val SVD(v, _, _) = breeze.linalg.svd(phi)
    val U: DenseMatrix[Double] = l(::, 0 until l.cols) * v

    U
  }

  private def extractEigenvalues(U: DenseMatrix[Double]) = {

    val d: DenseVector[Double] = DenseVector.zeros(U.cols)

    for (i <- 0 until U.cols) {
      d(i) = breeze.linalg.norm(U(::, i))
      U(::, i) := U(::, i) * 1.0 / d(i)
    }

    (U, pow(d, 2))

  }

  def computeApproximateEig(A: DenseMatrix[Double], D: Double, sc: StoppingCriterion) = {
    val kernel: (Int, Int) => Double = (i, j) => A(i, j)
    val indices = IndexedSeq.range(0, A.cols)

    extractEigenvalues(computeApproximateEigGeneric(kernel, indices, D, sc))
  }

  def computeApproximateEig[D <: Dim: NDSpace, DO <: Dim: NDSpace](kernel: MatrixValuedPDKernel[D, DO],
    xs: IndexedSeq[Point[D]], D: Double,
    stoppingCriterion: StoppingCriterion) = {

    case class PointWithDim(point: Point[D], dim: Int)
    val dim = NDSpace[DO].dimensionality
    val xsWithDim: IndexedSeq[PointWithDim] = xs.flatMap(f => (0 until dim).map(i => PointWithDim(f, i)))
    def kscalar(x: PointWithDim, y: PointWithDim): Double = kernel(x.point, y.point)(x.dim, y.dim)

    extractEigenvalues(computeApproximateEigGeneric[PointWithDim](kscalar, xsWithDim, D, stoppingCriterion))

  }

  def computeApproximateEig[D <: Dim: NDSpace, DO <: Dim: NDSpace](kernel: PDKernel[D],
    xs: IndexedSeq[Point[D]],
    D: Double,
    stoppingCriterion: StoppingCriterion) = {

    def k(x: Point[D], y: Point[D]): Double = kernel(x, y)

    extractEigenvalues(computeApproximateEigGeneric[Point[D]](k, xs, D, stoppingCriterion))

  }
}