/*
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

package org.apache.flink.ml.clustering

import breeze.linalg._
import breeze.numerics._
import org.apache.flink.api.scala._
import org.apache.flink.ml.common.{ParameterMap}
import org.apache.flink.ml.common._

import scala.util.Random

/**
 * Created by shakirullah on 5/20/15.
 */

case class Gaussians(id: Long, prior: Double, mu: DenseVector[Double], sigma: DenseMatrix[Double]) {}
case class Points(id: Long, pos: DenseVector[Double]) {}

case class Liklihood(pointID: Long, gaussianID: Long, probability: Double) {}
case class TotalLiklihood(pointID:Long,probTotal:Double){}
case class Prediction(gaussianID: Long, pointID: Long, probability: Double) {}

class GMM extends Learner[Points,GMModel] {

  import GMM._

  def setNumIterations(numIterations: Int): GMM = {
    parameters.add(NumIterations, numIterations)
    this
  }
  def setNumGaussians(numGaussians: Int): GMM = {
    parameters.add(NumGaussians, numGaussians)
    this
  }
  def setInitialGaussians(initialGaussians:DataSet[Gaussians]):GMM ={
    parameters.add(InitialGaussians,initialGaussians)
    this
  }

  override def fit(input: DataSet[Points], fitParameters: ParameterMap): GMModel = {

    val resultingParameters = this.parameters ++ fitParameters

    val numIterations: Int = resultingParameters.get(NumIterations).get
    val numPoints:Int=input.count().toInt
    val numDimensions:Int=input.collect().apply(0).pos.length
    val numGaussians:Int=resultingParameters.get(NumGaussians).get
    val initialGaussians:DataSet[Gaussians]=resultingParameters.get(InitialGaussians).get

    if(initialGaussians==None){
      // generate random gaussians if not provided by the user
      val gaussiansArray =(
          for (i <- 1 to numGaussians) yield Gaussians(
            i,
            1 / numGaussians,
            DenseVector.rand(numDimensions),
            DenseMatrix.rand(numDimensions, numDimensions))).toArray
      val initialGaussians= ExecutionEnvironment.getExecutionEnvironment.fromElements(gaussiansArray:_*)
    }


    val normGaussiansCovariance=(initialGaussians cross input).map(pair=>
    {
      val gaussian=pair._1  //gaussian
      val point=pair._2     //point
      Gaussians(gaussian.id,
                gaussian.prior,
                gaussian.mu,
                (point.pos-gaussian.mu) * new Transpose(point.pos-gaussian.mu))}) //sigma
        .groupBy(x=>x.id).reduce((g1,g2)=>{
               Gaussians(g1.id,g1.prior,g1.mu,g1.sigma+g2.sigma)})
        .map(g=>Gaussians(g.id,g.prior,g.mu, g.sigma / (numPoints-1.0)))


    val finalGaussians = normGaussiansCovariance.iterate(numIterations) { initialGaussians =>

      //E-Step
      //estimate the probability of sample 'pair' under the distribution of component 'gaussian'
      val pointsProb = (initialGaussians cross input)
        .map(pair => {
                      val c = pair._1 // gaussian
                      val x = pair._2 // point
                      val invSigma = inv(c.sigma)

                      //density function for gaussian distribution
                      val const = 1 / (sqrt(2 * Math.PI) * abs(det(c.sigma)))
                      val liklehood = const * exp(-0.5 * (new Transpose(x.pos-c.mu) * invSigma * (x.pos-c.mu))) * c.prior

        Liklihood(x.id, c.id, liklehood)})

      //total probability for sample 'x' generated by all 'gaussians'
      val liklihoodTotal = pointsProb
        .map(p => TotalLiklihood(p.pointID, p.probability))
        .groupBy(_.pointID)
        .reduce((p1, p2) => TotalLiklihood(p1.pointID, p1.probTotal + p2.probTotal))

      //compute the posteriori P(c|x) for sample 'x' to belong to gaussian 'c'
      val posterior = pointsProb
        .joinWithTiny(liklihoodTotal)
        .where(l => l.pointID)
        .equalTo(r => r.pointID)
        .apply((p1, p2) => {
        Prediction(p1.gaussianID, p1.pointID, p1.probability / p2.probTotal)
      })

      //M-Step maximize the parameters
      val weightedGaussians = posterior.joinWithTiny(input).where(l => l.pointID).equalTo(r => r.id)
        .map(pair => {
                        val post = pair._1  //posterior
                        val point = pair._2 //point
                        Gaussians(post.gaussianID,
                                  post.probability,
                                  post.probability * point.pos,
                                  post.probability * (point.pos * new Transpose(point.pos)))})
        .groupBy(x => x.id)
        .reduce((g1, g2) => Gaussians(g1.id, g1.prior + g2.prior, g1.mu + g2.mu, g1.sigma + g2.sigma))

      val sumPriors = weightedGaussians
         .map(g => g.prior).reduce((p1, p2) => p1 + p2)

      val newGaussians = (weightedGaussians cross sumPriors)
        .map(pair => {
                        val gaussian = pair._1 //gaussian
                        val sumPr = pair._2    //sum of priors
                        val gProiorTot = gaussian.prior
                        val mu = gaussian.mu / gProiorTot
                        val sigma = (gaussian.sigma / gProiorTot) - (mu * new Transpose(mu))
                        val prior = gProiorTot / sumPr
                        Gaussians(gaussian.id, prior, mu, sigma)})

          newGaussians
    }

    GMModel(finalGaussians)
  }
}
object GMM {
  case object NumIterations extends Parameter[Int] {
    val defaultValue = Some(10)
  }
  case object NumGaussians extends Parameter[Int]{
    val defaultValue=Some(2)
  }
  case object InitialGaussians extends Parameter[DataSet[Gaussians]]{
    val defaultValue=None
  }

  def apply():GMM={
    new GMM()
  }
}
case class GMModel(gmm: DataSet[Gaussians]) extends Transformer[Points, Prediction]
with Serializable {
  override def transform(input: DataSet[Points], parameters: ParameterMap):
  DataSet[Prediction] = {
    val pointsProb = (gmm cross input).map(pair => {
      val c = pair._1 // gaussian
      val x = pair._2 // point
      val invSigma = inv(c.sigma)

      //density function for gaussian distribution
      val const = 1 / (sqrt(2 * Math.PI) * abs(det(c.sigma)))
      val liklihood = const * exp(-0.5 * (new Transpose(x.pos-c.mu) * invSigma * (x.pos-c.mu))) * c.prior

      Liklihood(x.id, c.id, liklihood)
    })

    //total probability for sample 'x' generated by all 'gaussians'
    val liklihoodTotal = pointsProb
      .map(p => TotalLiklihood(p.pointID, p.probability))
      .groupBy(_.pointID)
      .reduce((p1, p2) => TotalLiklihood(p1.pointID, p1.probTotal + p2.probTotal))

    //Prediction for sample 'x' belong to gaussian 'c'
    val prediction = pointsProb
      .joinWithTiny(liklihoodTotal)
      .where(l => l.pointID)
      .equalTo(r => r.pointID)
      .apply((p1, p2) => Prediction(p1.gaussianID, p1.pointID, p1.probability / p2.probTotal))

    prediction
  }
}