/*******************************************************************************
 * The MIT License (MIT)
 * 
 * Copyright (c) 2011 - 2015 OpenWorm.
 * http://openworm.org
 * 
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the MIT License
 * which accompanies this distribution, and is available at
 * http://opensource.org/licenses/MIT
 *
 * Contributors:
 *     	OpenWorm - http://openworm.org/people.html
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights 
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell 
 * copies of the Software, and to permit persons to whom the Software is 
 * furnished to do so, subject to the following conditions:
 * 
 * The above copyright notice and this permission notice shall be included in 
 * all copies or substantial portions of the Software.
 * 
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR 
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY, 
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. 
 * IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, 
 * DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR 
 * OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE 
 * USE OR OTHER DEALINGS IN THE SOFTWARE.
 *******************************************************************************/
package org.geppetto.simulation;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.geppetto.core.common.GeppettoErrorCodes;
import org.geppetto.core.common.GeppettoExecutionException;
import org.geppetto.core.conversion.IConversion;
import org.geppetto.core.data.IGeppettoS3Manager;
import org.geppetto.core.data.model.ExperimentStatus;
import org.geppetto.core.data.model.IAspectConfiguration;
import org.geppetto.core.data.model.IExperiment;
import org.geppetto.core.data.model.IGeppettoProject;
import org.geppetto.core.data.model.ISimulationResult;
import org.geppetto.core.data.model.ISimulatorConfiguration;
import org.geppetto.core.model.quantities.PhysicalQuantity;
import org.geppetto.core.model.runtime.RuntimeTreeRoot;
import org.geppetto.core.model.runtime.VariableNode;
import org.geppetto.core.model.values.ValuesFactory;
import org.geppetto.core.simulation.IGeppettoManagerCallbackListener;
import org.geppetto.core.simulation.IGeppettoManagerCallbackListener.GeppettoEvents;
import org.geppetto.core.simulation.ISimulatorCallbackListener;
import org.geppetto.core.simulator.ISimulator;
import org.geppetto.simulation.visitor.ExitVisitor;
import org.geppetto.simulation.visitor.FindAspectNodeVisitor;
import org.geppetto.simulation.visitor.TimeVisitor;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * The ExperimentRun is created when an experiment can be executed, during the init phase all the needed services are created.
 * 
 * @author dandromereschi
 * @author matteocantarelli
 *
 */
public class ExperimentRunThread extends Thread implements ISimulatorCallbackListener
{

	private static Log logger = LogFactory.getLog(ExperimentRunThread.class);

	@Autowired
	public AppConfig appConfig;

	@Autowired
	private IGeppettoS3Manager s3Manager;

	private IExperiment experiment;

	private Map<String, ISimulator> simulatorServices = new ConcurrentHashMap<>();

	private Map<String, IConversion> conversionServices = new ConcurrentHashMap<>();

	private List<IExperimentListener> experimentListeners = new ArrayList<>();

	// This map contains the simulator runtime for each one of the simulators
	private Map<String, SimulatorRuntime> simulatorRuntimes = new ConcurrentHashMap<String, SimulatorRuntime>();

	private IGeppettoManagerCallbackListener simulationCallbackListener;

	private int updateCycles = 0;

	private long timeElapsed;

	private double runtime;

	private String timeStepUnit;

	private RuntimeExperiment runtimeExperiment;

	private IGeppettoProject project;

	/**
	 * @param dataManager
	 * @param experiment
	 * @param project
	 * @param runtimeExperiment2
	 * @param simulationCallbackListener
	 */
	public ExperimentRunThread(IExperiment experiment, RuntimeExperiment runtimeExperiment, IGeppettoProject project, IGeppettoManagerCallbackListener simulationCallbackListener)
	{
		this.experiment = experiment;
		this.runtimeExperiment = runtimeExperiment;
		this.project = project;
		init(experiment);
	}

	protected void addExperimentListener(IExperimentListener listener)
	{
		experimentListeners.add(listener);
	}

	protected void removeExperimentListener(IExperimentListener listener)
	{
		experimentListeners.remove(listener);
	}

	private void init(IExperiment experiment)
	{
		List<? extends IAspectConfiguration> aspectConfigs = experiment.getAspectConfigurations();
		for(IAspectConfiguration aspectConfig : aspectConfigs)
		{
			ISimulatorConfiguration simConfig = aspectConfig.getSimulatorConfiguration();
			String simulatorId = simConfig.getSimulatorId();
			String instancePath = aspectConfig.getAspect().getInstancePath();

			if(simConfig.getConversionServiceId() != null)
			{
				ServiceCreator<String, IConversion> scc = new ServiceCreator<String, IConversion>(simConfig.getConversionServiceId(), IConversion.class.getName(), instancePath, conversionServices);
				scc.run();
			}

			ServiceCreator<String, ISimulator> scs = new ServiceCreator<String, ISimulator>(simulatorId, ISimulator.class.getName(), instancePath, simulatorServices);
			Thread tscs = new Thread(scs);
			tscs.start();
			try
			{
				tscs.join();
			}
			catch(InterruptedException e)
			{
				simulationCallbackListener.error(GeppettoErrorCodes.INITIALIZATION, this.getClass().getName(), null, e);
			}
			if(simulatorId != null)
			{
				SimulatorRuntime simRuntime = new SimulatorRuntime();
				simulatorRuntimes.put(instancePath, simRuntime);
			}
		}
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see java.lang.Thread#run()
	 */
	public void run()
	{
		while(experiment.getStatus().equals(ExperimentStatus.RUNNING))
		{
			long calculateTime = System.currentTimeMillis() - timeElapsed;
			List<? extends IAspectConfiguration> aspectConfigs = experiment.getAspectConfigurations();

			// update only if time elapsed since last client update doesn't exceed
			// the update cycle of application.
			if(calculateTime >= updateCycles)
			{
				for(IAspectConfiguration aspectConfig : aspectConfigs)
				{
					String instancePath = aspectConfig.getAspect().getInstancePath();
					SimulatorRuntime simulatorRuntime = simulatorRuntimes.get(instancePath);
					ISimulator simulator = simulatorServices.get(instancePath);

					if(simulatorRuntime.getNonConsumedSteps() < appConfig.getMaxBufferSize())
					{
						// we advance the simulation for this simulator only if we don't have already
						// too many steps in the buffer
						try
						{
							simulatorRuntime.setStatus(SimulatorRuntimeStatus.STEPPING);
							FindAspectNodeVisitor findAspectNodeVisitor = new FindAspectNodeVisitor(instancePath);
							runtimeExperiment.getRuntimeTree().apply(findAspectNodeVisitor);
							SimulatorRunThread simulatorRunThread = new SimulatorRunThread(simulator, aspectConfig, findAspectNodeVisitor.getAspectNode());
							simulatorRunThread.start();
							simulator.simulate(aspectConfig, findAspectNodeVisitor.getAspectNode());
							simulatorRuntime.incrementStepsConsumed(); // TODO Remove?
						}
						catch(GeppettoExecutionException e)
						{
							throw new RuntimeException("Error while stepping " + simulator.getName(), e);
						}
					}
				}
				
				if(simulationCallbackListener != null)
				{
					//if it is null the client is disconnected
					sendSimulationCallback();
				}

				timeElapsed = System.currentTimeMillis();
				logger.info("Updating after " + calculateTime + " ms");
			}
		}

		// and when done, notify about it
		for(IExperimentListener listener : experimentListeners)
		{
			try
			{
				listener.experimentRunDone(this, experiment, project);
				storeResults(experiment);
			}
			catch(GeppettoExecutionException e)
			{
				throw new RuntimeException("Post run experiment error", e);
			}
		}

	}

	private boolean checkAllStepped()
	{
		boolean allStepped = true;
		boolean noneEverStepped = true;
		List<? extends IAspectConfiguration> aspectConfigs = experiment.getAspectConfigurations();
		for(IAspectConfiguration aspectConfig : aspectConfigs)
		{
			String instancePath = aspectConfig.getAspect().getInstancePath();
			if(allStepped || noneEverStepped)
			{
				SimulatorRuntime simulatorRuntime = simulatorRuntimes.get(instancePath);

				if(simulatorRuntime.getNonConsumedSteps() < 0)
				{
					// this simulator has no steps to consume
					allStepped = false;
				}

				if(simulatorRuntime.getProcessedSteps() != 0)
				{
					// this simulator has steps to consume
					noneEverStepped = false;
				}
			}
		}

		return allStepped;
	}

	/**
	 * Send update to client with new run time tree
	 */
	public void sendSimulationCallback()
	{
		if(checkAllStepped() && experiment.getStatus().equals(ExperimentStatus.RUNNING))
		{
			// Visit simulators to extract time from them
			TimeVisitor timeVisitor = new TimeVisitor();

			runtimeExperiment.getRuntimeTree().apply(timeVisitor);
			String timeStepUnit = timeVisitor.getTimeStepUnit();
			// set global time
			this.setGlobalTime(timeVisitor.getTime(), runtimeExperiment.getRuntimeTree());

			ExitVisitor exitVisitor = new ExitVisitor();
			runtimeExperiment.getRuntimeTree().apply(exitVisitor);
			
			simulationCallbackListener.updateReady(GeppettoEvents.EXPERIMENT_UPDATE,runtimeExperiment.getRuntimeTree());
			
		}
	}

	/**
	 * Updates the time node in the run time tree root node
	 * 
	 * @param newTimeValue
	 *            - New time
	 * @param tree
	 *            -Tree root node
	 */
	private void setGlobalTime(double newTimeValue, RuntimeTreeRoot tree)
	{
		runtime += newTimeValue;
		VariableNode time = new VariableNode("time");
		PhysicalQuantity t = new PhysicalQuantity();
		t.setValue(ValuesFactory.getDoubleValue(runtime));
		t.setUnit(timeStepUnit);
		time.addPhysicalQuantity(t);
		time.setParent(tree);
		tree.setTime(time);
	}

	/**
	 * @throws GeppettoExecutionException
	 */
	protected void cancelRun() throws GeppettoExecutionException
	{

		logger.info("Canceling ExperimentRun");

		// iterate through aspects and instruct them to stop
		// TODO Check
		for(ISimulator simulator : simulatorServices.values())
		{
			if(simulator != null)
			{
				simulator.setInitialized(false);
			}
		}
	}

	/**
	 * @param experiment
	 */
	private void storeResults(IExperiment experiment)
	{
		if(s3Manager != null)
		{
			for(ISimulationResult result : experiment.getSimulationResults())
			{
				// TODO Check path
				s3Manager.saveFileToS3(new File(result.getResult().getUrl()), "path");
			}
		}
	}

	/**
	 * 
	 */
	public void release()
	{
		simulatorServices.clear();
		conversionServices.clear();
		simulatorRuntimes.clear();
		experimentListeners.clear();
		simulationCallbackListener=null;
	}

	/* (non-Javadoc)
	 * @see org.geppetto.core.simulation.ISimulatorCallbackListener#endOfSteps(java.lang.String)
	 */
	@Override
	public void endOfSteps(String message, File recordingsLocation)
	{
		this.s3Manager.saveFileToS3(recordingsLocation, recordingsLocation.getAbsolutePath());
	}

	/* (non-Javadoc)
	 * @see org.geppetto.core.simulation.ISimulatorCallbackListener#stateTreeUpdated()
	 */
	@Override
	public void stateTreeUpdated() throws GeppettoExecutionException
	{
		// TODO Auto-generated method stub

	}

}
