/*
 *
 *  * Copyright 2015 Skymind,Inc.
 *  *
 *  *    Licensed under the Apache License, Version 2.0 (the "License");
 *  *    you may not use this file except in compliance with the License.
 *  *    You may obtain a copy of the License at
 *  *
 *  *        http://www.apache.org/licenses/LICENSE-2.0
 *  *
 *  *    Unless required by applicable law or agreed to in writing, software
 *  *    distributed under the License is distributed on an "AS IS" BASIS,
 *  *    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  *    See the License for the specific language governing permissions and
 *  *    limitations under the License.
 *
 */

package org.deeplearning4j.optimize.solvers;

import org.apache.commons.math3.util.FastMath;
import static org.nd4j.linalg.ops.transforms.Transforms.*;

import org.deeplearning4j.exception.InvalidStepException;
import org.deeplearning4j.nn.api.Model;
import org.deeplearning4j.optimize.api.ConvexOptimizer;
import org.deeplearning4j.optimize.api.StepFunction;
import org.deeplearning4j.optimize.stepfunctions.DefaultStepFunction;
import org.deeplearning4j.optimize.stepfunctions.NegativeDefaultStepFunction;
import org.nd4j.linalg.api.ndarray.INDArray;
import org.nd4j.linalg.api.ops.impl.scalar.comparison.ScalarSetValue;
import org.nd4j.linalg.api.ops.impl.transforms.comparison.Eps;
import org.nd4j.linalg.factory.Nd4j;
import org.deeplearning4j.optimize.api.LineOptimizer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


//"Line Searches and Backtracking", p385, "Numeric Recipes in C"
/**
 @author Aron Culotta <a href="mailto:culotta@cs.umass.edu">culotta@cs.umass.edu</a>

 Adapted from mallet with original authors above.
 Modified to be a vectorized version that uses jblas matrices
 for computation rather than the mallet ops.


 Numerical Recipes in C: p.385. lnsrch. A simple backtracking line
 search. No attempt at accurately finding the true minimum is
 made. The goal is only to ensure that BackTrackLineSearch will
 return a position of higher value.

 @author Adam Gibson


 */

public class BackTrackLineSearch implements LineOptimizer  {
    private static final Logger logger = LoggerFactory.getLogger(BackTrackLineSearch.class.getName());

    private Model layer;
    private StepFunction stepFunction = new DefaultStepFunction();
    private ConvexOptimizer optimizer;
    private int maxIterations = 5;
    double stpmax = 100;

    // termination conditions: either
    //   a) abs(delta x/x) < REL_TOLX for all coordinates
    //   b) abs(delta x) < ABS_TOLX for all coordinates
    //   c) sufficient function increase (uses ALF)
    private double relTolx = 1e-7f;
    private double absTolx = 1e-4f; // tolerance on absolute value difference
    final double ALF = 1e-4f;

    /**
     *
     * @param layer
     * @param stepFunction
     * @param optimizer
     */
    public BackTrackLineSearch(Model layer, StepFunction stepFunction, ConvexOptimizer optimizer) {
        this.layer = layer;
        this.stepFunction = stepFunction;
        this.optimizer = optimizer;
    }

    /**
     *
     * @param optimizable
     * @param optimizer
     */
    public BackTrackLineSearch(Model optimizable, ConvexOptimizer optimizer) {
        this(optimizable, new DefaultStepFunction(),optimizer);
    }


    public void setStpmax(double stpmax) {
        this.stpmax = stpmax;
    }


    public double getStpmax() {
        return stpmax;
    }

    /**
     * Sets the tolerance of relative diff in function value.
     *  Line search converges if abs(delta x / x) < tolx
     *  for all coordinates. */
    public void setRelTolx (double tolx) { relTolx = tolx; }

    /**
     * Sets the tolerance of absolute diff in function value.
     *  Line search converges if abs(delta x) < tolx
     *  for all coordinates. */
    public void setAbsTolx (double tolx) { absTolx = tolx; }

    public int getMaxIterations() {
        return maxIterations;
    }

    public void setMaxIterations(int maxIterations) {
        this.maxIterations = maxIterations;
    }

    // initialStep is ignored.  This is b/c if the initial step is not 1.0,
    //   it sometimes confuses the backtracking for reasons I don't
    //   understand.  (That is, the jump gets LARGER on iteration 1.)

    // returns fraction of step size (alam) if found a good step
    // returns 0.0 if could not step in direction

    private double getNewScore(INDArray parameters){
        layer.setParams(parameters);
        return layer.score();
    }

    /**
     *
     * @param initialStep the initial step size
     * @param parameters the parameters to optimize
     * @param gradients the line/rate of change
     * @param searchDirection  the point for the line search to go in
     * @return the next step size
     * @throws InvalidStepException
     */
    @Override
    public double optimize(double initialStep, INDArray parameters, INDArray gradients, INDArray searchDirection) throws InvalidStepException {
        double test, alamin, alam, alam2, oldAlam, tmplam;
        double rhs1, rhs2, a, b, disc, f, fold, f2;

        INDArray oldParameters = parameters.dup();
        double sum = searchDirection.norm2(Integer.MAX_VALUE).getDouble(0);

        INDArray maxOldParams = abs(oldParameters);
        Nd4j.getExecutioner().exec(new ScalarSetValue(maxOldParams, 1));
        INDArray testMatrix = abs(gradients).divi(maxOldParams);
        test = testMatrix.max(Integer.MAX_VALUE).getDouble(0);

        alam  = 1.0; // initially, alam = 1.0, i.e. take full Newton step
        alamin = relTolx / test; // relative convergence tolerance
        oldAlam = 0.0;
        alam2 = 0.0;

        f2 = fold = layer.score();

    	if( logger.isTraceEnabled() ){
            logger.trace ("ENTERING BACKTRACK\n");
            logger.trace("Entering BackTrackLinnSearch, value = " + fold + ",\ndirection.oneNorm:"
                    +	searchDirection.dup().norm1(Integer.MAX_VALUE) + "  direction.infNorm:"+
                    FastMath.max(Float.NEGATIVE_INFINITY, abs(searchDirection.dup()).max(Integer.MAX_VALUE).getDouble(0)));
        }

        if(sum > stpmax) {
            logger.warn("attempted step too big. scaling: sum= {}, stpmax= {}", sum, stpmax);
            searchDirection.muli(stpmax / sum);
        }
        double slope = Nd4j.getBlasWrapper().dot(searchDirection, gradients);

        logger.debug("slope = {}", slope);

        if (slope < 0)
            throw new InvalidStepException("Slope = " + slope + " is negative");
        else if (slope == 0)
            throw new InvalidStepException ("Slope = " + slope + " is zero");

        // find maximum lambda
        // converge when (delta x) / x < REL_TOLX for all coordinates.
        // the largest step size that triggers this threshold is precomputed and saved in alamin
        // look for step size in direction given by "line"

        for(int iteration = 0; iteration < maxIterations; iteration++) {
        	if( logger.isTraceEnabled() ){
        		logger.trace("BackTrack loop iteration {} : alam={}, oldAlam={}", iteration, alam, oldAlam);
        		logger.trace("before step, x.1norm: {} \nalam: {} \noldAlam: {}", parameters.norm1(Integer.MAX_VALUE), alam, oldAlam);
        	}
            assert(alam != oldAlam) : "alam == oldAlam";

            if(stepFunction == null)
                stepFunction =  new NegativeDefaultStepFunction();
            //scale wrt updates
            stepFunction.step(parameters, searchDirection, new Object[]{alam}); //step
            oldAlam = alam;

            if(logger.isDebugEnabled())  {
                double norm1 = parameters.norm1(Integer.MAX_VALUE).getDouble(0);
                logger.debug("after step, x.1norm: " + norm1);
            }

            // check for convergence on delta x
            // if all of the parameters are < 1e-12

            if ((alam < alamin) || Nd4j.getExecutioner().execAndReturn(new Eps(oldParameters, parameters,
                    parameters.dup(), parameters.length())).sum(Integer.MAX_VALUE).getDouble(0) == parameters.length()) {
                f = getNewScore(oldParameters);
                logger.trace("EXITING BACKTRACK: Jump too small (alamin = {}). Exiting and using xold. Value = {}", alamin, f);
                return 0.0;
            }

            f = getNewScore(parameters);
            logger.debug("Model score after step = {}", f);

            //Sufficient decrease in cost/loss function (Wolfe condition / Armijo condition)
            if(f <= fold + ALF * alam * slope) {
                logger.debug("Sufficient decrease, exiting backtrack: score={}, oldScore={}",f,fold);
                if (f > fold)
                    throw new IllegalStateException
                            ("Function did not decrease: f = " + f + " > " + fold + " = fold");
                return alam;
            }

            // if value is infinite, i.e. we've
            // jumped to unstable territory, then scale down jump
            else if(Double.isInfinite(f) || Double.isInfinite(f2)) {
                logger.warn("Value is infinite after jump. oldAlam={}. f={}, f2={}. Scaling back step size...",oldAlam,f,f2);
                tmplam = .2 * alam;
                if(alam < alamin) { //convergence on delta x
                    f = getNewScore(oldParameters);
                    logger.warn("EXITING BACKTRACK: Jump too small. Exiting and using xold. Value={}", f);
                    return 0.0;
                }
            }

            // backtrack
            else {
                if(alam == 1.0) // first time through
                    tmplam = -slope / (2.0 * ( f - fold - slope ));
                else {
                    rhs1 = f - fold - alam * slope;
                    rhs2 = f2 - fold - alam2 * slope;
                    if(alam == alam2)
                        throw new IllegalStateException("FAILURE: dividing by alam-alam2. alam=" + alam);
                    double alamSquared = alam*alam;
                    double alam2Squared = alam2*alam2;
                    a = ( rhs1/alamSquared - rhs2/alam2Squared ) / (alam - alam2);
                    b = ( -alam2*rhs1/alamSquared + alam*rhs2/alam2Squared ) / (alam - alam2);
                    if(a == 0.0)
                        tmplam = -slope / (2.0 * b);
                    else {
                        disc = b * b - 3.0 * a * slope;
                        if(disc < 0.0) {
                            tmplam = 0.5 * alam;
                        }
                        else if (b <= 0.0)
                            tmplam = (-b + FastMath.sqrt(disc))/(3.0 * a );
                        else
                            tmplam = -slope / (b +FastMath.sqrt(disc));
                    }
                    if (tmplam > 0.5 * alam)
                        tmplam = 0.5 * alam;    // lambda <= 0.5 lambda_1
                }
            }

            alam2 = alam;
            f2 = f;
            logger.debug("tmplam: {}", tmplam);
            alam = Math.max(tmplam, .1f * alam);  // lambda >= .1*Lambda_1
        }
        
        logger.debug("Exited line search after maxIterations termination condition");
        return 0.0;
    }




}

