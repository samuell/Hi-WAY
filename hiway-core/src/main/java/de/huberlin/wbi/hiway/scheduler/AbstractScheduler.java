/*******************************************************************************
 * In the Hi-WAY project we propose a novel approach of executing scientific
 * workflows processing Big Data, as found in NGS applications, on distributed
 * computational infrastructures. The Hi-WAY software stack comprises the func-
 * tional workflow language Cuneiform as well as the Hi-WAY ApplicationMaster
 * for Apache Hadoop 2.x (YARN).
 *
 * List of Contributors:
 *
 * Jörgen Brandt (HU Berlin)
 * Marc Bux (HU Berlin)
 * Ulf Leser (HU Berlin)
 *
 * Jörgen Brandt is funded by the European Commission through the BiobankCloud
 * project. Marc Bux is funded by the Deutsche Forschungsgemeinschaft through
 * research training group SOAMED (GRK 1651).
 *
 * Copyright 2014 Humboldt-Universität zu Berlin
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
 ******************************************************************************/
package de.huberlin.wbi.hiway.scheduler;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.Map;
import java.util.Queue;
import java.util.Set;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.fs.FileStatus;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.yarn.api.records.Container;
import org.apache.hadoop.yarn.api.records.ContainerId;
import org.apache.hadoop.yarn.api.records.ContainerStatus;

import de.huberlin.hiwaydb.useDB.HiwayDB;
import de.huberlin.hiwaydb.useDB.HiwayDBI;
import de.huberlin.hiwaydb.useDB.InvocStat;
import de.huberlin.wbi.cuneiform.core.semanticmodel.JsonReportEntry;
import de.huberlin.wbi.hiway.app.HiWayConfiguration;
import de.huberlin.wbi.hiway.common.Constant;
import de.huberlin.wbi.hiway.common.TaskInstance;
//import de.huberlin.wbi.hiway.scheduler.C3PO.ConservatismEstimate;
//import de.huberlin.wbi.hiway.scheduler.C3PO.Estimate;

/**
 * An abstract implementation of a workflow scheduler.
 * 
 * @author Marc Bux
 * 
 */
public abstract class AbstractScheduler implements Scheduler {

	protected class Estimate {
		String taskName;
		double weight = 1d;
	}

	protected class RuntimeEstimate extends Estimate {
		double averageRuntime;
		int finishedTasks;
		// int remainingTasks;
		// int runningTasks;
		double timeSpent;
	}

	private static final Log log = LogFactory.getLog(AbstractScheduler.class);

	// node -> task -> invocstats
	// protected Map<String, Map<String, Set<InvocStat>>> invocStats;
	protected HiwayDBI dbInterface;

	protected Map<String, Long> maxTimestampPerHost;

	protected int numberOfPreviousRunTasks = 0;
	protected int numberOfFinishedTasks = 0;
	protected int numberOfRemainingTasks = 0;
	protected int numberOfRunningTasks = 0;

	// private Set<String> nodeIds;
	protected Set<Long> taskIds;
	protected Map<String, Map<Long, RuntimeEstimate>> runtimeEstimatesPerNode;

	// protected Map<String, Map<Long, Long>> totalRuntimes;
	// protected Map<String, Map<Long, Long>> nExecutions;
	// protected Map<String, Map<Long, Double>> runtimeEstimates;

	// a queue of nodes on which containers are to be requested
	protected Queue<String[]> unissuedNodeRequests;
	protected String workflowName;
	protected HiWayConfiguration conf;

	public AbstractScheduler(String workflowName, HiWayConfiguration conf) {
		// statistics = new HashMap<Long, Map<String, Set<InvocStat>>>();
		this.workflowName = workflowName;
		this.conf = conf;
		unissuedNodeRequests = new LinkedList<>();

		String dbType = conf.get(HiWayConfiguration.HIWAY_DB_TYPE, HiWayConfiguration.HIWAY_DB_TYPE_DEFAULT);
		switch (dbType) {
		case HiWayConfiguration.HIWAY_DB_TYPE_SQL:
			String username = conf.get(HiWayConfiguration.HIWAY_DB_USER);
			String password = conf.get(HiWayConfiguration.HIWAY_DB_PASSWORD);
			String dbURL = conf.get(HiWayConfiguration.HIWAY_DB_URL);
			dbInterface = new HiwayDB(username, password, dbURL);
			break;
		default:
			dbInterface = new LogParser();
		}

		taskIds = new HashSet<>();
		runtimeEstimatesPerNode = new HashMap<>();
		maxTimestampPerHost = new HashMap<>();
	}

	@Override
	public void addEntryToDB(JsonReportEntry entry) {
		log.info("HiwayDB: Adding entry " + entry + " to database.");
		dbInterface.logToDB(entry);
	}

	protected void addTask(TaskInstance task) {
		numberOfRemainingTasks++;
	}

	@Override
	public void addTasks(Collection<TaskInstance> tasks) {
		for (TaskInstance task : tasks) {
			addTask(task);
		}
	}

	@Override
	public void addTaskToQueue(TaskInstance task) {
		unissuedNodeRequests.add(new String[0]);
	}

	@Override
	public String[] getNextNodeRequest() {
		return unissuedNodeRequests.remove();
	}

	@Override
	public TaskInstance getNextTask(Container container) {
		numberOfRemainingTasks--;
		numberOfRunningTasks++;
		return null;
	}

	protected Set<String> getNodeIds() {
		return new HashSet<>(runtimeEstimatesPerNode.keySet());
	}

	@Override
	public int getNumberOfFinishedTasks() {
		return numberOfFinishedTasks - numberOfPreviousRunTasks;
	}

	@Override
	public int getNumberOfRunningTasks() {
		return numberOfRunningTasks;
	}

	@Override
	public int getNumberOfTotalTasks() {
		log.debug("Update on total tasks:");
		int finishedTasks = getNumberOfFinishedTasks();
		log.debug("\tfinished:  " + finishedTasks);
		int runningTasks = getNumberOfRunningTasks();
		log.debug("\trunning:   " + runningTasks);
		log.debug("\tremaining: " + numberOfRemainingTasks);
		return finishedTasks + runningTasks + numberOfRemainingTasks
				- numberOfPreviousRunTasks;
	}

	protected Set<Long> getTaskIds() {
		return new HashSet<>(taskIds);
	}

	@Override
	public boolean hasNextNodeRequest() {
		return !unissuedNodeRequests.isEmpty();
	}

	protected void newHost(String nodeId) {
		Map<Long, RuntimeEstimate> runtimeEstimates = new HashMap<>();
		for (long taskId : getTaskIds()) {
			runtimeEstimates.put(taskId, new RuntimeEstimate());
		}
		runtimeEstimatesPerNode.put(nodeId, runtimeEstimates);
		maxTimestampPerHost.put(nodeId, 0l);
	}

	protected void newTask(long taskId) {
		taskIds.add(taskId);
		for (Map<Long, RuntimeEstimate> runtimeEstimates : runtimeEstimatesPerNode
				.values()) {
			runtimeEstimates.put(taskId, new RuntimeEstimate());
		}
	}

	@Override
	public boolean nothingToSchedule() {
		return getNumberOfReadyTasks() == 0;
	}

	protected void parseLogs(FileSystem fs) {
		Path hiwayDir = new Path(fs.getHomeDirectory(), Constant.SANDBOX_DIRECTORY);
		try {
			for (FileStatus appDirStatus : fs.listStatus(hiwayDir)) {
				if (appDirStatus.isDirectory()) {
					Path appDir = appDirStatus.getPath();
					for (FileStatus srcStatus : fs.listStatus(appDir)) {
						Path src = srcStatus.getPath();
						String srcName = src.getName();
						if (srcName.equals(conf.get(HiWayConfiguration.HIWAY_STAT_LOG, HiWayConfiguration.HIWAY_STAT_LOG_DEFAULT))) {
							log.info("Parsing log " + src.toString());
							Path dest = new Path(srcName);
							fs.copyToLocalFile(false, src, dest);

							try (BufferedReader reader = new BufferedReader(
									new FileReader(new File(srcName)))) {
								String line;
								while ((line = reader.readLine()) != null) {
									JsonReportEntry entry = new JsonReportEntry(line);
									log.info("HiwayDB: Adding entry " + entry + " to database.");
									dbInterface.logToDB(entry);
									// log.info(line);
								}
							}
						}
					}
				}
			}
		} catch (Exception e) {
			e.printStackTrace();
		}
		updateRuntimeEstimates();
		numberOfPreviousRunTasks += getNumberOfFinishedTasks();
	}

	@Override
	public boolean relaxLocality() {
		return true;
	}

	@Override
	public Collection<ContainerId> taskCompleted(TaskInstance task,
			ContainerStatus containerStatus, long runtimeInMs) {

		numberOfRunningTasks--;
		numberOfFinishedTasks++;

		log.info("Task " + task + " in container "
				+ containerStatus.getContainerId().getId() + " finished after "
				+ runtimeInMs + " ms");

		return new ArrayList<>();
	}

	@Override
	public Collection<ContainerId> taskFailed(TaskInstance task,
			ContainerStatus containerStatus) {
		numberOfRunningTasks--;

		log.info("Task " + task + " on container "
				+ containerStatus.getContainerId().getId() + " failed");
		if (task.retry()) {
			log.info("Retrying task " + task + ".");
			addTask(task);
		} else {
			log.info("Task "
					+ task
					+ " has exceeded maximum number of allowed retries. Aborting task.");
		}

		return new ArrayList<>();
	}

	protected void updateRuntimeEstimate(InvocStat stat) {
		RuntimeEstimate re = runtimeEstimatesPerNode.get(stat.getHostName())
				.get(stat.getTaskId());
		re.finishedTasks += 1;
		re.timeSpent += stat.getRealTime();
		re.weight = re.averageRuntime = re.timeSpent / re.finishedTasks;
	}

	protected void updateRuntimeEstimates() {
		Collection<String> newHostIds = dbInterface.getHostNames();
		newHostIds.removeAll(getNodeIds());
		for (String newHostId : newHostIds) {
			newHost(newHostId);
		}
		Collection<Long> newTaskIds = dbInterface
				.getTaskIdsForWorkflow(workflowName);

		newTaskIds.removeAll(getTaskIds());
		for (long newTaskId : newTaskIds) {
			newTask(newTaskId);
		}

		for (long taskId : getTaskIds()) {
			for (String hostName : getNodeIds()) {
				long maxTimestamp = maxTimestampPerHost.get(hostName);
				for (InvocStat stat : dbInterface
						.getLogEntriesForTaskOnHostSince(taskId, hostName,
								maxTimestamp)) {
					log.info("HiwayDB: Retrieved InvocStat " + stat.toString() + " from database.");
					maxTimestampPerHost.put(hostName,
							Math.max(maxTimestamp, stat.getTimestamp()));
					updateRuntimeEstimate(stat);
				}
			}
		}
	}
}
