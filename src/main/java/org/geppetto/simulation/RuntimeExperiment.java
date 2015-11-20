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
import java.io.IOException;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.StringTokenizer;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.eclipse.emf.common.util.TreeIterator;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.util.EcoreUtil;
import org.geppetto.core.common.GeppettoExecutionException;
import org.geppetto.core.common.GeppettoInitializationException;
import org.geppetto.core.common.HDF5Reader;
import org.geppetto.core.data.DataManagerHelper;
import org.geppetto.core.data.model.ExperimentStatus;
import org.geppetto.core.data.model.IAspectConfiguration;
import org.geppetto.core.data.model.IExperiment;
import org.geppetto.core.data.model.IInstancePath;
import org.geppetto.core.data.model.IParameter;
import org.geppetto.core.data.model.ISimulationResult;
import org.geppetto.core.data.model.ResultsFormat;
import org.geppetto.core.library.LibraryManager;
import org.geppetto.core.manager.IGeppettoManager;
import org.geppetto.core.manager.Scope;
import org.geppetto.core.model.IModel;
import org.geppetto.core.model.IModelInterpreter;
import org.geppetto.core.model.RecordingModel;
import org.geppetto.core.model.typesystem.values.ACompositeValue;
import org.geppetto.core.model.typesystem.values.CompositeValue;
import org.geppetto.core.model.typesystem.values.ParameterValue;
import org.geppetto.core.model.typesystem.values.SkeletonAnimationValue;
import org.geppetto.core.model.typesystem.values.VariableValue;
import org.geppetto.core.model.typesystem.visitor.SetWatchedVariablesVisitor;
import org.geppetto.core.services.DropboxUploadService;
import org.geppetto.core.services.ModelFormat;
import org.geppetto.core.simulator.RecordingReader;
import org.geppetto.core.utilities.URLReader;
import org.geppetto.model.GeppettoModel;
import org.geppetto.model.util.GeppettoModelTraversal;
import org.geppetto.simulation.visitor.CreateInstanceTreeVisitor;
import org.geppetto.simulation.visitor.CreateModelInterpreterServicesVisitor;
import org.geppetto.simulation.visitor.DownloadModelVisitor;
import org.geppetto.simulation.visitor.FindAspectNodeVisitor;
import org.geppetto.simulation.visitor.FindModelTreeVisitor;
import org.geppetto.simulation.visitor.FindParameterSpecificationNodeVisitor;
import org.geppetto.simulation.visitor.LoadGeppettoModelVisitor;
import org.geppetto.simulation.visitor.SetParametersVisitor;
import org.geppetto.simulation.visitor.SupportedOutputsVisitor;

public class RuntimeExperiment
{

	private Map<String, IModelInterpreter> modelInterpreters = new HashMap<String, IModelInterpreter>();

	private Map<String, IModel> instancePathToIModelMap = new HashMap<>();

	private GeppettoModel geppettoModel;

	private IExperiment experiment;

	private IGeppettoManager geppettoManager;

	private static LibraryManager libraryManager;

	private static Log logger = LogFactory.getLog(RuntimeExperiment.class);

	public RuntimeExperiment(RuntimeProject runtimeProject, IExperiment experiment) throws GeppettoExecutionException
	{
		this.experiment = experiment;
		geppettoManager = runtimeProject.getGeppettoManager();
		libraryManager = new LibraryManager();
		geppettoModel=runtimeProject.getGeppettoModel();
		init();
	}


	
	private void init() throws GeppettoExecutionException
	{
		this.clearWatchLists();

		// retrieve model interpreters and simulators
		CreateModelInterpreterServicesVisitor createServicesVisitor = new CreateModelInterpreterServicesVisitor(modelInterpreters, experiment.getParentProject().getId(), geppettoManager.getScope());
		GeppettoModelTraversal.apply(geppettoModel, createServicesVisitor);

		
		CreateInstanceTreeVisitor runtimeTreeVisitor = new CreateInstanceTreeVisitor(modelInterpreters, libraryManager);
		GeppettoModelTraversal.apply(geppettoModel, runtimeTreeVisitor);


		// let's set the parameters if they exist
		for(IAspectConfiguration ac : experiment.getAspectConfigurations())
		{
			if(ac.getModelParameter() != null && !ac.getModelParameter().isEmpty())
			{
				populateModelTree(ac.getAspect().getInstancePath());
				setModelParameters(ac.getAspect().getInstancePath(), ac.getModelParameter());
			}
		}

	}

	public Map<String, IModel> getInstancePathToIModelMap()
	{
		return instancePathToIModelMap;
	}

	public Map<String, IModelInterpreter> getModelInterpreters()
	{
		return modelInterpreters;
	}

	/**
	 * 
	 */
	public void clearWatchLists()
	{
		logger.info("Clearing watched variables in simulation tree");

		// Update the RunTimeTreeModel setting watched to false for every node
		SetWatchedVariablesVisitor clearWatchedVariablesVisitor = new SetWatchedVariablesVisitor();
		geppettoModel.apply(clearWatchedVariablesVisitor);

		if(experiment.getStatus().equals(ExperimentStatus.DESIGN))
		{
			// if we are still in design we ask the DataManager to change what we are watching
			// TODO Do we need "recordedVariables"? Thinking of the scenario that we recorded many and we
			// only want to get a portion of them in the client
			List<? extends IAspectConfiguration> aspectConfigs = experiment.getAspectConfigurations();
			for(IAspectConfiguration aspectConfig : aspectConfigs)
			{
				DataManagerHelper.getDataManager().clearWatchedVariables(aspectConfig);
			}
		}
		else
		{
			// TODO Exception or we change the "watched" and keep the "recorded"?
		}

	}

	/**
	 * @param watchedVariables
	 * @throws GeppettoExecutionException
	 * @throws GeppettoInitializationException
	 */
	public void setWatchedVariables(List<String> watchedVariables)
	{
		logger.info("Setting watched variables in simulation tree");

		// Update the RunTimeTreeModel
		SetWatchedVariablesVisitor setWatchedVariablesVisitor = new SetWatchedVariablesVisitor(experiment, watchedVariables);
		geppettoModel.apply(setWatchedVariablesVisitor);
		DataManagerHelper.getDataManager().saveEntity(experiment);

	}

	/**
	 * 
	 */
	public void release()
	{
		modelInterpreters.clear();
		instancePathToIModelMap.clear();
		geppettoModel = null;
		geppettoManager = null;
	}


	/**
	 * @return
	 */
	public Root getRuntimeTree()
	{
		return geppettoModel;
	}

	/**
	 * @param aspectInstancePath
	 * @return
	 * @throws GeppettoExecutionException
	 */
	public File downloadModel(String aspectInstancePath, ModelFormat format) throws GeppettoExecutionException
	{
		logger.info("Downloading Model for " + aspectInstancePath + " in format " + format);

		DownloadModelVisitor downloadModelVistor = new DownloadModelVisitor(aspectInstancePath, format, getAspectConfiguration(experiment, aspectInstancePath));
		geppettoModel.apply(downloadModelVistor);
		downloadModelVistor.postProcessVisit();
		return downloadModelVistor.getModelFile();
	}

	/**
	 * @param aspectInstancePath
	 * @return
	 * @throws GeppettoExecutionException
	 */
	public List<ModelFormat> supportedOuputs(String aspectInstancePath) throws GeppettoExecutionException
	{
		logger.info("Getting supported outputs for " + aspectInstancePath);
		SupportedOutputsVisitor supportedOutputsModelVisitor = new SupportedOutputsVisitor(aspectInstancePath);
		geppettoModel.apply(supportedOutputsModelVisitor);
		supportedOutputsModelVisitor.postProcessVisit();
		return supportedOutputsModelVisitor.getSupportedOutputs();
	}

	/**
	 * @return
	 * @throws GeppettoExecutionException
	 */
	public Map<String, AspectSubTreeNode> updateRuntimeTreesWithResults() throws GeppettoExecutionException
	{
		Map<String, AspectSubTreeNode> loadedResults = new HashMap<String, AspectSubTreeNode>();
		for(ISimulationResult result : experiment.getSimulationResults())
		{
			if(result.getFormat().equals(ResultsFormat.GEPPETTO_RECORDING))
			{
				URL url;
				try
				{
					url = URLReader.getURL(result.getResult().getUrl());
				}
				catch(IOException e)
				{
					throw new GeppettoExecutionException(e);
				}

				RecordingReader recordingReader = new RecordingReader(new RecordingModel(HDF5Reader.readHDF5File(url, experiment.getParentProject().getId())), result.getFormat());

				// get all aspect configurations
				List<IAspectConfiguration> aspectConfigs = (List<IAspectConfiguration>) experiment.getAspectConfigurations();

				// get all watched variables from all aspect configurations
				List<IInstancePath> watchedVariables = new ArrayList<IInstancePath>();
				for(IAspectConfiguration aspectConfig : aspectConfigs)
				{
					for(IInstancePath ip : aspectConfig.getWatchedVariables())
					{
						watchedVariables.add(ip);
					}
				}

				if(watchedVariables.size() > 0)
				{
					// after reading values out from recording, amp to the correct aspect given the watched variable
					for(IInstancePath watchedVariable : watchedVariables)
					{
						logger.info("Reading results for " + watchedVariable.getInstancePath());

						// Retrieve aspect node for current watched variable
						FindAspectNodeVisitor findAspectNodeVisitor = new FindAspectNodeVisitor(watchedVariable.getEntityInstancePath() + "."
								+ watchedVariable.getAspect().substring(0, watchedVariable.getAspect().indexOf(".")));
						geppettoModel.apply(findAspectNodeVisitor);
						AspectNode aspect = findAspectNodeVisitor.getAspectNode();
						aspect.setModified(true);
						aspect.getParentEntity().setModified(true);

						// Create variable node in Aspect tree
						AspectTreeType treeType = watchedVariable.getAspect().contains(AspectTreeType.SIMULATION_TREE.toString()) ? AspectTreeType.SIMULATION_TREE : AspectTreeType.VISUALIZATION_TREE;

						// We first need to populate the simulation tree for the given aspect
						// NOTE: it would seem that commenting this line out makes no difference - remove?
						String instancePath = aspect.getInstancePath();
						populateSimulationTree(instancePath);

						AspectSubTreeNode simulationTree = (AspectSubTreeNode) aspect.getSubTree(AspectTreeType.SIMULATION_TREE);
						AspectSubTreeNode visualizationTree = (AspectSubTreeNode) aspect.getSubTree(AspectTreeType.VISUALIZATION_TREE);

						recordingReader.readRecording(watchedVariable, treeType == AspectTreeType.SIMULATION_TREE ? simulationTree : visualizationTree, true);

						String aspectPath = watchedVariable.getEntityInstancePath() + "."
								+ watchedVariable.getAspect().replace("." + AspectTreeType.SIMULATION_TREE.toString(), "").replace("." + AspectTreeType.VISUALIZATION_TREE.toString(), "");

						// map results to the appropriate tree
						loadedResults.put(aspectPath, treeType == AspectTreeType.SIMULATION_TREE ? simulationTree : visualizationTree);

						if(treeType == AspectTreeType.SIMULATION_TREE)
						{
							simulationTree.setModified(true);
						}
						else
						{
							visualizationTree.setModified(true);
						}

						logger.info("Finished reading results for " + watchedVariable.getInstancePath());
					}

				}
			}
		}
		return loadedResults;
	}

	/**
	 * @param experiment
	 * @param instancePath
	 * @return
	 */
	private IAspectConfiguration getAspectConfiguration(IExperiment experiment, String instancePath)
	{
		// Check if it is a subAspect Instance Path and extract the base one
		String[] instancePathSplit = instancePath.split("\\.");
		if(instancePathSplit.length > 2)
		{
			instancePath = instancePathSplit[0] + "." + instancePathSplit[2];
		}

		for(IAspectConfiguration aspectConfig : experiment.getAspectConfigurations())
		{
			if(aspectConfig.getAspect().getInstancePath().equals(instancePath))
			{
				return aspectConfig;
			}
		}
		return null;
	}

	/**
	 * Creates variables to store in simulation tree
	 * 
	 * @param variables
	 * @param tree
	 */
	public void createVariables(IInstancePath variable, AspectSubTreeNode tree)
	{
		String path = "/" + variable.getInstancePath().replace(tree.getInstancePath() + ".", "");
		path = path.replace(".", "/");

		path = path.replaceFirst("/", "");
		StringTokenizer tokenizer = new StringTokenizer(path, "/");
		ACompositeValue node = tree;
		while(tokenizer.hasMoreElements())
		{
			String current = tokenizer.nextToken();
			boolean found = false;
			for(ANode child : node.getChildren())
			{
				if(child.getId().equals(current))
				{
					if(child instanceof ACompositeValue)
					{
						node = (ACompositeValue) child;
					}

					found = true;
					break;
				}
			}
			if(found)
			{
				continue;
			}
			else
			{
				if(tokenizer.hasMoreElements())
				{
					// not a leaf, create a composite state node
					ACompositeValue newNode = new CompositeValue(current);
					node.addChild(newNode);
					node = newNode;
				}
				else
				{
					// it's a leaf node
					if(tree.getType() == AspectTreeType.SIMULATION_TREE)
					{
						// for now leaf nodes in the Sim tree can only be variable nodes
						VariableValue newNode = new VariableValue(current);
						newNode.setWatched(true);
						node.addChild(newNode);
					}
					else if(tree.getType() == AspectTreeType.VISUALIZATION_TREE)
					{
						// for now leaf nodes in the Viz tree can only be skeleton animation nodes
						SkeletonAnimationValue newNode = new SkeletonAnimationValue(current);
						node.addChild(newNode);
					}
				}
			}
		}
	}

	/**
	 * @param instancePath
	 * @param modelParameter
	 * @return
	 * @throws GeppettoExecutionException
	 */
	private AspectSubTreeNode setModelParameters(String instancePath, List<? extends IParameter> modelParameter) throws GeppettoExecutionException
	{
		Map<String, String> parameters = new HashMap<String, String>();
		for(IParameter p : modelParameter)
		{
			parameters.put(p.getVariable().getInstancePath(), p.getValue());
		}
		return setModelParameters(instancePath, parameters);
	}

	/**
	 * @param modelAspectPath
	 * @param parameters
	 * @return
	 * @throws GeppettoExecutionException
	 */
	public AspectSubTreeNode setModelParameters(String modelAspectPath, Map<String, String> parameters) throws GeppettoExecutionException
	{
		SetParametersVisitor parameterVisitor = new SetParametersVisitor(parameters, modelAspectPath);
		IAspectConfiguration config = this.getAspectConfiguration(experiment, modelAspectPath);
		for(String path : parameters.keySet())
		{
			IParameter existingParameter = null;
			for(IParameter p : config.getModelParameter())
			{
				if(p.getVariable().getInstancePath().equals(path))
				{
					existingParameter = p;
					break;
				}
			}
			if(existingParameter != null)
			{
				existingParameter.setValue(parameters.get(path));
			}
			else
			{
				FindParameterSpecificationNodeVisitor findParameterVisitor = new FindParameterSpecificationNodeVisitor(path);
				geppettoModel.apply(findParameterVisitor);
				ParameterValue p = findParameterVisitor.getParameterNode();
				if(p != null)
				{
					IInstancePath instancePath = DataManagerHelper.getDataManager().newInstancePath(p.getEntityInstancePath(), p.getAspectInstancePath(), p.getLocalInstancePath());
					config.addModelParameter(DataManagerHelper.getDataManager().newParameter(instancePath, parameters.get(path)));
				}
				else
				{
					throw new GeppettoExecutionException("Cannot find parameter " + path + "in the runtime tree.");
				}
			}
		}
		geppettoModel.apply(parameterVisitor);
		parameterVisitor.postProcessVisit();
		FindModelTreeVisitor findParameterVisitor = new FindModelTreeVisitor(modelAspectPath + ".ModelTree");
		geppettoModel.apply(findParameterVisitor);

		return findParameterVisitor.getModelTreeNode();

	}

	/**
	 * @param aspectID
	 * @param format
	 * @param dropboxService
	 * @throws GeppettoExecutionException
	 */
	public void uploadResults(String aspectID, ResultsFormat format, DropboxUploadService dropboxService) throws GeppettoExecutionException
	{
		for(ISimulationResult result : experiment.getSimulationResults())
		{
			if(result.getAspect().getInstancePath().equals(aspectID))
			{
				if(result.getFormat().equals(format))
				{
					URL url;
					try
					{
						url = URLReader.getURL(result.getResult().getUrl());
						dropboxService.upload(new File(URLReader.createLocalCopy(Scope.CONNECTION, experiment.getParentProject().getId(), url).toURI()));
					}
					catch(Exception e)
					{
						throw new GeppettoExecutionException(e);
					}
				}
			}
		}
	}

}
