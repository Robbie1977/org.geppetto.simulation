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
import java.net.MalformedURLException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.geppetto.core.common.GeppettoExecutionException;
import org.geppetto.core.common.GeppettoInitializationException;
import org.geppetto.core.data.DataManagerHelper;
import org.geppetto.core.data.model.ExperimentStatus;
import org.geppetto.core.data.model.IExperiment;
import org.geppetto.core.data.model.IGeppettoProject;
import org.geppetto.core.data.model.IUser;
import org.geppetto.core.manager.IGeppettoManager;
import org.geppetto.core.model.runtime.AspectSubTreeNode;
import org.geppetto.core.model.runtime.RuntimeTreeRoot;
import org.geppetto.core.services.IModelFormat;
import org.geppetto.core.simulation.IExperimentRunManager;
import org.geppetto.core.simulation.IGeppettoManagerCallbackListener;
import org.geppetto.core.simulation.ResultsFormat;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * GeppettoManager is the implementation of IGeppettoManager which represents the Java API entry point for Geppetto. This class is instantiated with a session scope, which means there is one
 * GeppettoManager per each session/connection therefore only one user is associated with a GeppettoManager. A GeppettoManager is also instantiated by the ExperimentRunManager to handle the queued
 * activities in the database.
 * 
 * @author dandromereschi
 * @author matteocantarelli
 * 
 */
// @Component
// @Scope(value="session",proxyMode=ScopedProxyMode.TARGET_CLASS)
public class GeppettoManager implements IGeppettoManager
{

	private static Log logger = LogFactory.getLog(GeppettoManager.class);

	// these are the runtime projects for a
	private Map<IGeppettoProject, RuntimeProject> projects = new LinkedHashMap<>();

	private IGeppettoManagerCallbackListener geppettoManagerCallbackListener;

	// The experiment run manager is used to queue an experiment that needs to be executed
	@Autowired
	private IExperimentRunManager experimentRunManager;

	private IUser user;

	/*
	 * (non-Javadoc)
	 * 
	 * @see org.geppetto.core.manager.IProjectManager#loadProject(java.lang.String, org.geppetto.core.data.model.IGeppettoProject)
	 */
	public void loadProject(String requestId, IGeppettoProject project) throws MalformedURLException, GeppettoInitializationException, GeppettoExecutionException
	{
		// RuntimeProject is created and populated when loadProject is called
		RuntimeProject runtimeProject = new RuntimeProject(project, geppettoManagerCallbackListener);
		projects.put(project, runtimeProject);
		// load the active experiment if there is one
		// TODO active experiment needs to be added to IGeppettoProject?
		if(project.getExperiments().size() > 0)
		{
			// loadExperiment(requestId, user, project.getExperiments().get(0));
		}
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see org.geppetto.core.manager.IProjectManager#closeProject(java.lang.String, org.geppetto.core.data.model.IGeppettoProject)
	 */
	public void closeProject(String requestId, IGeppettoProject project) throws GeppettoExecutionException
	{
		if(!projects.containsKey(project) && projects.get(project) == null)
		{
			throw new GeppettoExecutionException("A project without a runtime project cannot be closed");
		}
		if(projects.get(project).getActiveExperiment() != null)
		{
			throw new GeppettoExecutionException("A project with an active experiment cannot be closed");
		}

		projects.remove(project);
	}

	/**
	 * @param project
	 * @return
	 * @throws GeppettoExecutionException
	 */
	public RuntimeProject getRuntimeProject(IGeppettoProject project) throws GeppettoExecutionException
	{
		if(!projects.containsKey(project))
		{
			throw new GeppettoExecutionException("The project with ID:" + project.getId() + " and Name:" + project.getName() + "is not loaded");
		}
		return projects.get(project);
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see org.geppetto.core.manager.IExperimentManager#loadExperiment(java.lang.String, org.geppetto.core.data.model.IExperiment, org.geppetto.core.data.model.IGeppettoProject)
	 */
	@Override
	public RuntimeTreeRoot loadExperiment(String requestId, IExperiment experiment, IGeppettoProject project) throws GeppettoExecutionException
	{
		 try {
			 for(IGeppettoProject proj : projects.keySet())
			 {
			 if(proj.getExperiments().contains(experiment))
			 {
			 project = proj;
			 }
			 }
			 if(!projects.containsKey(project) && projects.get(project) == null)
			 {
			 throw new GeppettoExecutionException("A project without a runtime project cannot be closed");
			 }
			 experimentRunManager.queueExperiment(user, experiment, project);
			 getRuntimeProject(project).openExperiment(requestId, experiment);
		} catch (MalformedURLException | GeppettoInitializationException e) {
			e.printStackTrace();
		}
		 	
		return getRuntimeProject(project).getRuntimeExperiment(experiment).getRuntimeTree();

	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see org.geppetto.core.manager.IExperimentManager#runExperiment(java.lang.String, org.geppetto.core.data.model.IExperiment, org.geppetto.core.data.model.IGeppettoProject)
	 */
	@Override
	public void runExperiment(String requestId, IExperiment experiment, IGeppettoProject project) throws GeppettoExecutionException
	{
		if(experiment.getStatus().equals(ExperimentStatus.DESIGN))
		{
			experimentRunManager.queueExperiment(user, experiment, project);
		}
		else
		{
			throw new GeppettoExecutionException("Cannot run an experiment whose status is not design");
		}
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see org.geppetto.core.manager.IProjectManager#deleteProject(java.lang.String, org.geppetto.core.data.model.IGeppettoProject)
	 */
	@Override
	public void deleteProject(String requestId, IGeppettoProject project) throws GeppettoExecutionException
	{
		DataManagerHelper.getDataManager().deleteGeppettoProject(project);
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see org.geppetto.core.manager.IProjectManager#persistProject(java.lang.String, org.geppetto.core.data.model.IGeppettoProject)
	 */
	@Override
	public void persistProject(String requestId, IGeppettoProject project)
	{
		DataManagerHelper.getDataManager().addGeppettoProject(project);
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see org.geppetto.core.manager.IExperimentManager#newExperiment(java.lang.String, org.geppetto.core.data.model.IGeppettoProject)
	 */
	@Override
	public IExperiment newExperiment(String requestId, IGeppettoProject project)
	{
		return DataManagerHelper.getDataManager().newExperiment("experiment " + (project.getExperiments().size() + 1), "");
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see org.geppetto.core.manager.IExperimentManager#deleteExperiment(java.lang.String, org.geppetto.core.data.model.IExperiment, org.geppetto.core.data.model.IGeppettoProject)
	 */
	@Override
	public void deleteExperiment(String requestId, IExperiment experiment, IGeppettoProject project)
	{
		project.getExperiments().remove(experiment);
		DataManagerHelper.getDataManager().deleteExperiment(experiment);
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see org.geppetto.core.manager.IDropBoxManager#linkDropBoxAccount()
	 */
	@Override
	public void linkDropBoxAccount()
	{
		// TODO Auto-generated method stub

	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see org.geppetto.core.manager.IDropBoxManager#uploadModelToDropBox(java.lang.String, org.geppetto.core.services.IModelFormat)
	 */
	@Override
	public void uploadModelToDropBox(String aspectID, IExperiment experiment, IGeppettoProject project, IModelFormat format)
	{
		// TODO Auto-generated method stub

	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see org.geppetto.core.manager.IDropBoxManager#uploadResultsToDropBox(java.lang.String, org.geppetto.core.simulation.ResultsFormat)
	 */
	@Override
	public void uploadResultsToDropBox(String aspectID, IExperiment experiment, IGeppettoProject project, ResultsFormat format)
	{
		// TODO Auto-generated method stub

	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see org.geppetto.core.manager.IRuntimeTreeManager#getModelTree(java.lang.String, org.geppetto.core.data.model.IExperiment, org.geppetto.core.data.model.IGeppettoProject)
	 */
	@Override
	public Map<String, AspectSubTreeNode> getModelTree(String aspectInstancePath, IExperiment experiment, IGeppettoProject project) throws GeppettoExecutionException
	{
		return getRuntimeProject(project).getRuntimeExperiment(experiment).populateModelTree(aspectInstancePath);
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see org.geppetto.core.manager.IRuntimeTreeManager#getSimulationTree(java.lang.String, org.geppetto.core.data.model.IExperiment, org.geppetto.core.data.model.IGeppettoProject)
	 */
	@Override
	public Map<String, AspectSubTreeNode> getSimulationTree(String aspectInstancePath, IExperiment experiment, IGeppettoProject project) throws GeppettoExecutionException
	{
		return getRuntimeProject(project).getRuntimeExperiment(experiment).populateSimulationTree(aspectInstancePath);
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see org.geppetto.core.manager.IRuntimeTreeManager#setModelParameters(java.lang.String, java.util.Map, org.geppetto.core.data.model.IExperiment, org.geppetto.core.data.model.IGeppettoProject)
	 */
	@Override
	public Map<String, String> setModelParameters(String aspectInstancePath, Map<String, String> parameters, IExperiment experiment, IGeppettoProject project)
	{
		// TODO Auto-generated method stub
		return null;
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see org.geppetto.core.manager.IRuntimeTreeManager#setSimulatorConfiguration(java.lang.String, java.util.Map, org.geppetto.core.data.model.IExperiment,
	 * org.geppetto.core.data.model.IGeppettoProject)
	 */
	@Override
	public Map<String, String> setSimulatorConfiguration(String aspectInstancePath, Map<String, String> parameters, IExperiment experiment, IGeppettoProject project)
	{
		// TODO Auto-generated method stub
		return null;
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see org.geppetto.core.manager.IRuntimeTreeManager#setWatchedVariables(java.util.List, org.geppetto.core.data.model.IExperiment, org.geppetto.core.data.model.IGeppettoProject)
	 */
	@Override
	public void setWatchedVariables(List<String> watchedVariables, IExperiment experiment, IGeppettoProject project) throws GeppettoExecutionException
	{
		// TODO Auto-generated method stub

	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see org.geppetto.core.manager.IRuntimeTreeManager#clearWatchLists(org.geppetto.core.data.model.IExperiment, org.geppetto.core.data.model.IGeppettoProject)
	 */
	@Override
	public void clearWatchLists(IExperiment experiment, IGeppettoProject project)
	{
		// TODO Auto-generated method stub

	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see org.geppetto.core.manager.IDownloadManager#downloadModel(java.lang.String, org.geppetto.core.services.IModelFormat)
	 */
	@Override
	public File downloadModel(String aspectID, IModelFormat format, IExperiment experiment, IGeppettoProject project)
	{
		// TODO Auto-generated method stub
		return null;
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see org.geppetto.core.manager.IDownloadManager#downloadResults(org.geppetto.core.simulation.ResultsFormat)
	 */
	@Override
	public File downloadResults(ResultsFormat resultsFormat, IExperiment experiment, IGeppettoProject project)
	{
		// TODO Auto-generated method stub
		return null;
	}

	public IUser getUser()
	{
		return user;
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see org.geppetto.core.manager.IGeppettoManager#setUser(org.geppetto.core.data.model.IUser)
	 */
	@Override
	public void setUser(IUser user) throws GeppettoExecutionException
	{
		if(this.user != null)
		{
			String message = "A GeppettoManager is being reused, an user was already set and setUser is being called. Current user:" + this.user.getName() + ", attempted new user:" + user.getName();
			logger.error(message);
			throw new GeppettoExecutionException(message);
		}
		this.user = user;
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see org.geppetto.core.manager.IGeppettoManager#setCallback(org.geppetto.core.simulation.IGeppettoManagerCallbackListener)
	 */
	@Override
	public void setCallback(IGeppettoManagerCallbackListener geppettoManagerCallbackListener)
	{
		this.geppettoManagerCallbackListener = geppettoManagerCallbackListener;

	}

	@Override
	public void cancelExperimentRun(String requestId, IExperiment experiment, IGeppettoProject project)
	{
		// TODO Auto-generated method stub

	}

}
