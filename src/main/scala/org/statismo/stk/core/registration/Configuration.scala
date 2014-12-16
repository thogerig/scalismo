package org.statismo.stk.core.registration

import org.statismo.stk.core.numerics.Optimizer
import org.statismo.stk.core.numerics.GradientDescentOptimizer
import org.statismo.stk.core.numerics.GradientDescentOptimizer
import org.statismo.stk.core.image.DiscreteImageDomain
import breeze.linalg.DenseVector
import org.statismo.stk.core.numerics.GradientDescentConfiguration




object Configuration {  
//case class KernelRegistration1D(domain : DiscreteImageDomain[_1D]) extends RegistrationConfiguration[CoordVector1D] {
//
//	val gk = GaussianKernel1D(0.1)
//	val gp = (domain : DiscreteImageDomain[CoordVector1D]) => GaussianProcess[CoordVector1D]((x: Point1D) => DenseVector(0.), gk)
//	val regularizationWeight = 0.0
//    
//  
//	  def optimizer = GradientDescentOptimizer(GradientDescentConfiguration(100, 0.001))
//	  def metric = MeanSquaresMetric1D(MeanSquaresMetricConfiguration()) 
//	  def transformationSpace = KernelTransformationSpace1D(KernelTransformationSpaceConfiguration(100, 500, gp))(domain)
//	  def regularizer = RKHSNormRegularizer
//	  def initialParameters = DenseVector.zeros[Double](100)
//}
}