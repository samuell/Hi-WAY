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
package de.huberlin.wbi.hiway.logstats;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;

import org.json.JSONException;
import org.json.JSONObject;

import de.huberlin.hiwaydb.useDB.HiwayDBI;
import de.huberlin.wbi.cuneiform.core.semanticmodel.JsonReportEntry;
import de.huberlin.wbi.hiway.common.Constant;

/*
 * Parses a log generated by Cuneiform / Hi-WAY and emits 
 * 
 * Overview - For all tasks combined:
 * (1) Idle Time (time in which a container could be allocated but is not)
 * (2) Scheduling time
 * (2) Container Startup / Stagein
 * (3) Container Shutdown / Stageout
 * (4) Execution time
 * 
 * Contribution to Runtime: total container uptime per task
 * 
 * Task Execution Characteristics: Startup vs Execution vs Shutdown
 * 
 */
public class LogParser {

	public class OnsetTimestampComparator implements Comparator<Invocation> {
		@Override
		public int compare(Invocation o1, Invocation o2) {
			return Long.compare(o1.getExecTimestamp(), o2.getExecTimestamp());
		}
	}

	public class JsonReportEntryComparatorByTimestamp implements
			Comparator<JsonReportEntry> {
		@Override
		public int compare(JsonReportEntry o1, JsonReportEntry o2) {
			return Long.compare(o1.getTimestamp(), o2.getTimestamp());
		}
	}

	public static void main(String[] args) {
		for (int i = 0; i < args.length; i++) {
			LogParser logParser = new LogParser(args[i]);
			try {
				logParser.firstPass();
				logParser.secondPass();
				logParser.thirdPass();
			} catch (JSONException | IOException e) {
				e.printStackTrace();
			}
			// logParser.printIncrements("bowtie2-align", "dbis11:8042");
			logParser.printStatistics(i == 0);
		}
	}

	List<JsonReportEntry> entries;

	private File file;
	private Map<Long, Invocation> invocations;

	private WorkfowRun run;

	public LogParser(String fileName) {
		file = new File(fileName);
		invocations = new HashMap<>();
		run = new WorkfowRun();
		// invocationsByContainer = new HashMap<>();
	}

	private void removeDuplicates() {
		entries = new LinkedList<>(new LinkedHashSet<>(entries));
	}

	private void removeBadContainers() throws JSONException {
		Set<String> badContainers = new HashSet<>();
		List<JsonReportEntry> badEntries = new LinkedList<>();

		for (JsonReportEntry entry : entries) {
			if (entry.getKey().equals(HiwayDBI.KEY_HIWAY_EVENT)) {
				JSONObject value = entry.getValueJsonObj();
				if (value.getString("type").equals("container-completed")) {
					if (value.getInt("exit-code") != 0) {
						badContainers.add(value.getString("container-id"));
						badEntries.add(entry);
					}
				}
			}
		}

		for (JsonReportEntry entry : entries) {
			if (entry.getKey().equals(HiwayDBI.KEY_HIWAY_EVENT)) {
				JSONObject value = entry.getValueJsonObj();
				if (value.getString("type").equals("container-allocated")) {
					if (badContainers.contains(value.getString("container-id"))) {
						badEntries.add(entry);
					}
				}
			}
		}

		entries.removeAll(badEntries);
	}

	private void expandEntry(JsonReportEntry incomplete,
			JsonReportEntry complete) {
		incomplete.setInvocId(complete.getInvocId());
		incomplete.setLang(complete.getLang());
		incomplete.setTaskId(complete.getTaskId());
		incomplete.setTaskname(complete.getTaskName());
	}

	private void expandHiwayEvents() throws JSONException {
		Queue<JsonReportEntry> execQ = new LinkedList<>();
		Map<String, JsonReportEntry> allocatedMap = new HashMap<>();
		Queue<JsonReportEntry> completedQ = new LinkedList<>();

		for (JsonReportEntry entry : entries) {
			switch (entry.getKey()) {
			case JsonReportEntry.KEY_INVOC_EXEC:
				execQ.add(entry);
				break;

			case JsonReportEntry.KEY_INVOC_TIME:
				JsonReportEntry completed = completedQ.remove();
				JSONObject value = completed.getValueJsonObj();
				JsonReportEntry allocated = allocatedMap.get(value
						.getString("container-id"));
				expandEntry(completed, entry);
				expandEntry(allocated, entry);
				break;

			case HiwayDBI.KEY_HIWAY_EVENT:
				value = entry.getValueJsonObj();
				switch (value.getString("type")) {
				case "container-requested":
					expandEntry(entry, execQ.remove());
					break;

				case "container-allocated":
					allocatedMap.put(value.getString("container-id"), entry);
					break;

				case "container-completed":
					completedQ.add(entry);
					break;
				}
				break;
			}
		}
	}

	public void firstPass() throws IOException, JSONException {
		entries = new LinkedList<>();
		try (BufferedReader reader = new BufferedReader(new FileReader(file))) {
			String line;
			while ((line = reader.readLine()) != null) {
				JsonReportEntry entry = new JsonReportEntry(line);
				entries.add(entry);
			}
		}

		removeDuplicates();
		removeBadContainers();
		expandHiwayEvents();
		Collections.sort(entries, new JsonReportEntryComparatorByTimestamp());

		printToFile(new File("output"));
	}

	private String lifecycle(Collection<Invocation> invocs, boolean idleTime) {
		long sched = 0;
		long startup = 0;
		long stagein = 0;
		long exec = 0;
		long stageout = 0;
		long shutdown = 0;
		for (Invocation invoc : invocs) {
			sched += invoc.getSchedTime();
			startup += invoc.getStartupTime();
			stagein += invoc.getStageinTime();
			exec += invoc.getExecTime();
			stageout += invoc.getStageoutTime();
			shutdown += invoc.getShutdownTime();
		}
		String lifecycle = sched + "\t" + startup + "\t" + stagein + "\t"
				+ exec + "\t" + stageout + "\t" + shutdown + "\t";
		long idle = run.getMaxConcurrentNodes()
				* (run.getRuntime())
				- run.getNoTaskReadyTime() - sched - startup - stagein - exec
				- stageout - shutdown;
		return idleTime ? idle + "\t" + lifecycle : lifecycle;
	}

	private String lifecycleHeaders(String category, boolean idleTime) {
		String pre = category + " idle\t";
		String post = category + " scheduling\t" + category + " startup\t"
				+ category + " stage-in\t" + category + " execution\t"
				+ category + " stage-out\t" + category + " shutdown\t";
		return idleTime ? pre + post : post;
	}

	public void printIncrements(String printTask, String printHost) {
		Map<String, Map<String, List<Invocation>>> invocsByHostAndTask = new HashMap<>();
		for (Invocation invocation : invocations.values()) {
			String task = invocation.getTaskName();
			String host = invocation.getHostName();

			Map<String, List<Invocation>> invocsByHost;
			if (!invocsByHostAndTask.containsKey(task)) {
				invocsByHost = new HashMap<>();
				invocsByHostAndTask.put(task, invocsByHost);
			}
			invocsByHost = invocsByHostAndTask.get(task);

			List<Invocation> invocs;
			if (!invocsByHost.containsKey(host)) {
				invocs = new ArrayList<>();
				invocsByHost.put(host, invocs);
			}
			invocs = invocsByHost.get(host);

			invocs.add(invocation);
		}

		try (BufferedWriter writer = new BufferedWriter(new FileWriter(
				new File("output"), true))) {
			List<Invocation> invocs = invocsByHostAndTask.get(printTask).get(
					printHost);
			Collections.sort(invocs, new OnsetTimestampComparator());
			for (int i = 0; i < invocs.size() - 1; i++) {
				Invocation invocA = invocs.get(i);
				Invocation invocB = invocs.get(i + 1);
				writer.write((invocB.getExecTime() - invocA.getExecTime())
						+ "\t" + (invocB.getFileSize() - invocA.getFileSize())
						+ "\n");
			}
		} catch (IOException e) {
			e.printStackTrace();
		}
	}

	public void printStatistics(boolean printHeaders) {
		Map<String, Set<Invocation>> invocationsByTaskname = new HashMap<>();
		for (Invocation invocation : invocations.values()) {
			String taskname = invocation.getTaskName();
			if (!invocationsByTaskname.containsKey(taskname)) {
				Set<Invocation> newSet = new HashSet<>();
				invocationsByTaskname.put(taskname, newSet);
			}
			invocationsByTaskname.get(taskname).add(invocation);
		}
		
		List<String> taskNames = new LinkedList<>(invocationsByTaskname.keySet());
		Collections.sort(taskNames);

		if (printHeaders) {
			System.out.print("runtime\t");
			System.out.print(lifecycleHeaders("total", true));
			for (String taskname : taskNames) {
				System.out.print(lifecycleHeaders(taskname, false));
			}
			System.out.println();
		}

		System.out.print(run.getRuntime() + "\t");
		System.out.print(lifecycle(invocations.values(), true));
		for (String taskname : taskNames) {
			System.out.print(lifecycle(invocationsByTaskname.get(taskname),
					false));
		}
		System.out.println();
	}

	private void printToFile(File output) throws IOException {
		try (BufferedWriter writer = new BufferedWriter(new FileWriter(output))) {
			for (JsonReportEntry entry : entries) {
				writer.write(entry.toString() + "\n");
			}
		}
	}

	public void secondPass() throws JSONException {
		int maxContainers = 0;
		int currentContainers = 0;
		for (JsonReportEntry entry : entries) {
			if (entry.getInvocId() != null
					&& !invocations.containsKey(entry.getInvocId())) {
				invocations.put(entry.getInvocId(),
						new Invocation(entry.getTaskName()));
			}
			Invocation invocation = invocations.get(entry.getInvocId());
			switch (entry.getKey()) {
			case HiwayDBI.KEY_HIWAY_EVENT:
				JSONObject value = entry.getValueJsonObj();
				switch (value.getString("type")) {
				case "container-allocated":
					invocation.setStartupTimestamp(entry.getTimestamp());
					currentContainers++;
					maxContainers = Math.max(currentContainers, maxContainers);
					break;

				case "container-completed":
					invocation.setShutdownTimestamp(entry.getTimestamp());
					currentContainers--;
					break;
				}
				break;

			case HiwayDBI.KEY_INVOC_TIME_SCHED:
				invocation.setSchedTime(entry.getValueJsonObj().getLong(
						"realTime"));
				break;

			case HiwayDBI.KEY_INVOC_HOST:
				invocation.setHostName(entry.getValueRawString());
				break;

			case JsonReportEntry.KEY_INVOC_TIME:
				invocation.setExecTime(entry.getValueJsonObj().getLong(
						"realTime"));
				invocation.setExecTimestamp(entry.getTimestamp());
				break;

			case HiwayDBI.KEY_INVOC_TIME_STAGEIN:
				invocation.setStageinTime(entry.getValueJsonObj().getLong(
						"realTime"));
				invocation.setStageinTimestamp(entry.getTimestamp());
				break;

			case HiwayDBI.KEY_INVOC_TIME_STAGEOUT:
				invocation.setStageoutTime(entry.getValueJsonObj().getLong(
						"realTime"));
				invocation.setStageoutTimestamp(entry.getTimestamp());
				break;

			case HiwayDBI.KEY_WF_NAME:
				run.setRunOnsetTimestamp(entry.getTimestamp());
				break;

			case HiwayDBI.KEY_WF_TIME:
				run.setRuntime(Long.parseLong(entry.getValueRawString()));
				break;

			case JsonReportEntry.KEY_FILE_SIZE_STAGEIN:
				invocations.get(entry.getInvocId()).addFileSize(
						Long.parseLong(entry.getValueRawString()));
			}
		}

		run.setMaxConcurrentNodes(maxContainers);
	}

	public void thirdPass() throws JSONException {

		// time in which a container has got no work to do, as there is
		// currently no task ready to execute
		long noTaskReadyTime = 0;
		long lastTimestamp = run.getRunOnsetTimestamp();

		int readyTasks = 0;

		for (JsonReportEntry entry : entries) {
			switch (entry.getKey()) {
			case JsonReportEntry.KEY_INVOC_EXEC:
				readyTasks++;
				break;
			case HiwayDBI.KEY_HIWAY_EVENT:
				JSONObject value = entry.getValueJsonObj();
				switch (value.getString("type")) {
				case "container-completed":
					readyTasks--;
					break;
				}
				break;
			}
			long timestamp = entry.getTimestamp();
			noTaskReadyTime += Math.max(0, run.getMaxConcurrentNodes()
					- readyTasks)
					* (timestamp - lastTimestamp);
			lastTimestamp = timestamp;
		}

		run.setNoTaskReadyTime(noTaskReadyTime);

	}

}
