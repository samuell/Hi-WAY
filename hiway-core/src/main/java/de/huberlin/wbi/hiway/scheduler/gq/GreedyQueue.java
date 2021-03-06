/*******************************************************************************
 * In the Hi-WAY project we propose a novel approach of executing scientific
 * workflows processing Big Data, as found in NGS applications, on distributed
 * computational infrastructures. The Hi-WAY software stack comprises the func-
 * tional workflow language Cuneiform as well as the Hi-WAY ApplicationMaster
 * for Apache Hadoop 2.x (YARN).
 *
 * List of Contributors:
 *
 * Marc Bux (HU Berlin)
 * Jörgen Brandt (HU Berlin)
 * Hannes Schuh (HU Berlin)
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
package de.huberlin.wbi.hiway.scheduler.gq;

import java.util.LinkedList;
import java.util.Queue;

import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.yarn.api.records.Container;

import de.huberlin.wbi.hiway.common.HiWayConfiguration;
import de.huberlin.wbi.hiway.common.TaskInstance;
import de.huberlin.wbi.hiway.scheduler.Scheduler;

/**
 * A basic implementation of a scheduler that stores ready-to-execute tasks in a queue. Whenever a container has been allocated, this container is greedily
 * assigned the first task from the queue.
 * 
 * @author Marc Bux
 * 
 */
public class GreedyQueue extends Scheduler {

	private Queue<TaskInstance> queue;

	public GreedyQueue(String workflowName, HiWayConfiguration conf, FileSystem hdfs) {
		super(workflowName, conf, hdfs);
		queue = new LinkedList<>();
	}

	@Override
	protected void addTask(TaskInstance task) {
		numberOfRemainingTasks++;
		if (task.readyToExecute())
			addTaskToQueue(task);
	}

	@Override
	public void addTaskToQueue(TaskInstance task) {
		unissuedNodeRequests.add(new String[0]);
		queue.add(task);
		System.out.println("Added task " + task + " to queue");
	}

	@Override
	public TaskInstance getNextTask(Container container) {
		numberOfRemainingTasks--;
		numberOfRunningTasks++;
		TaskInstance task = queue.remove();

		System.out.println("Assigned task " + task + " to container " + container.getId().getContainerId() + " on node " + container.getNodeId().getHost());
		task.incTries();
		return task;
	}

	@Override
	public int getNumberOfReadyTasks() {
		return queue.size();
	}

}
