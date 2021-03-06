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
package de.huberlin.wbi.hiway.common;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Vector;

import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.GnuParser;
import org.apache.commons.cli.HelpFormatter;
import org.apache.commons.cli.Options;
import org.apache.commons.cli.ParseException;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.io.DataOutputBuffer;
import org.apache.hadoop.security.Credentials;
import org.apache.hadoop.security.UserGroupInformation;
import org.apache.hadoop.security.token.Token;
import org.apache.hadoop.yarn.api.ApplicationConstants;
import org.apache.hadoop.yarn.api.ApplicationConstants.Environment;
import org.apache.hadoop.yarn.api.protocolrecords.GetNewApplicationResponse;
import org.apache.hadoop.yarn.api.records.ApplicationId;
import org.apache.hadoop.yarn.api.records.ApplicationReport;
import org.apache.hadoop.yarn.api.records.ApplicationSubmissionContext;
import org.apache.hadoop.yarn.api.records.ContainerLaunchContext;
import org.apache.hadoop.yarn.api.records.FinalApplicationStatus;
import org.apache.hadoop.yarn.api.records.NodeReport;
import org.apache.hadoop.yarn.api.records.NodeState;
import org.apache.hadoop.yarn.api.records.Priority;
import org.apache.hadoop.yarn.api.records.QueueACL;
import org.apache.hadoop.yarn.api.records.QueueInfo;
import org.apache.hadoop.yarn.api.records.QueueUserACLInfo;
import org.apache.hadoop.yarn.api.records.Resource;
import org.apache.hadoop.yarn.api.records.YarnApplicationState;
import org.apache.hadoop.yarn.api.records.YarnClusterMetrics;
import org.apache.hadoop.yarn.client.api.YarnClient;
import org.apache.hadoop.yarn.client.api.YarnClientApplication;
import org.apache.hadoop.yarn.conf.YarnConfiguration;
import org.apache.hadoop.yarn.exceptions.YarnException;
import org.apache.hadoop.yarn.util.Records;

/**
 * Hi-WAY Client for workflow submission to Hadoop YARN.
 */
public class Client {

	/**
	 * The main routine.
	 * 
	 * @param args
	 *            Command line arguments passed to the Client.
	 */
	public static void main(String[] args) {
		boolean result = false;
		try {
			Client client = new Client();
			System.out.println("Initializing Client");
			try {
				boolean doRun = client.init(args);
				if (!doRun) {
					System.exit(0);
				}
			} catch (IllegalArgumentException e) {
				client.printUsage();
				e.printStackTrace();
				System.exit(-1);
			}
			result = client.run();
		} catch (Throwable t) {
			System.err.println("Error running Client");
			t.printStackTrace();
			System.exit(-1);
		}
		if (result) {
			System.out.println("Application completed successfully");
			System.exit(0);
		}
		System.err.println("Application failed to complete successfully");
		System.exit(2);
	}

	// amount of memory resource to request for to run the App Master
	private int amMemory = 4096;
	// the priority of the AM container
	private int amPriority = 0;
	// the queue to which this application is to be submitted in the RM
	private String amQueue = "";
	// start time for client
	private final long clientStartTime = System.currentTimeMillis();
	// timeout threshold for client. Kill app after time interval expires.
	private long clientTimeout;
	private HiWayConfiguration conf;
	// debug flag
	boolean debugFlag = false;
	private FileSystem hdfs;
	// command line options
	private Options opts;
	private Data summary;
	private Path summaryPath;
	// the workflow format and its path in the file system
	private Data workflow;
	private Path workflowPath;

	private HiWayConfiguration.HIWAY_WORKFLOW_LANGUAGE_OPTS workflowType;
	// a handle to the YARN ApplicationsManager (ASM)
	private YarnClient yarnClient;

	public Client() {
		conf = new HiWayConfiguration();
		yarnClient = YarnClient.createYarnClient();
		yarnClient.init(conf);
		opts = new Options();
		opts.addOption("s", "summary", true, "The name of the json summary file. No file is created if this parameter is not specified.");
		opts.addOption("w", "workflow", true, "The workflow file to be executed by the Application Master");
		String workflowFormats = "";
		for (HiWayConfiguration.HIWAY_WORKFLOW_LANGUAGE_OPTS language : HiWayConfiguration.HIWAY_WORKFLOW_LANGUAGE_OPTS.values()) {
			workflowFormats += ", " + language.toString();
		}
		opts.addOption("l", "language", true, "The input file format. Valid arguments: " + workflowFormats.substring(2) + ". Default: "
				+ HiWayConfiguration.HIWAY_WORKFLOW_LANGUAGE_OPTS.cuneiform);
		opts.addOption("debug", false, "Dump out debug information");
		opts.addOption("help", false, "Print usage");
	}

	/**
	 * Kill a submitted application by sending a call to the ASM.
	 * 
	 * @param appId
	 *            Application Id to be killed.
	 * 
	 * @throws YarnException
	 * @throws IOException
	 */
	private void forceKillApplication(ApplicationId appId) throws YarnException, IOException {
		// Response can be ignored as it is non-null on success or throws an exception in case of failures
		yarnClient.killApplication(appId);
	}

	/**
	 * Parse command line options.
	 * 
	 * @param args
	 *            Parsed command line options.
	 * @return Whether the init was successful to run the client.
	 * @throws ParseException
	 */
	public boolean init(String[] args) throws ParseException {

		try {
			hdfs = FileSystem.get(conf);
		} catch (IOException e) {
			e.printStackTrace();
			System.exit(-1);
		}

		CommandLine cliParser = new GnuParser().parse(opts, args);

		if (args.length == 0) {
			throw new IllegalArgumentException("No args specified for client to initialize");
		}

		if (cliParser.hasOption("help")) {
			printUsage();
			return false;
		}

		if (cliParser.hasOption("debug")) {
			debugFlag = true;
		}

		amPriority = conf.getInt(HiWayConfiguration.HIWAY_AM_PRIORITY, HiWayConfiguration.HIWAY_AM_PRIORITY_DEFAULT);
		amQueue = conf.get(HiWayConfiguration.HIWAY_AM_QUEUE, HiWayConfiguration.HIWAY_AM_QUEUE_DEFAULT);
		amMemory = conf.getInt(HiWayConfiguration.HIWAY_AM_MEMORY, HiWayConfiguration.HIWAY_AM_MEMORY_DEFAULT);
		if (amMemory < 0) {
			throw new IllegalArgumentException("Invalid memory specified for application master, exiting." + " Specified memory=" + amMemory);
		}

		if (cliParser.hasOption("summary")) {
			try {
				summaryPath = new Path(new File(cliParser.getOptionValue("summary")).getCanonicalPath());
			} catch (IOException e) {
				e.printStackTrace();
				System.exit(-1);
			}
		}

		try {
			workflowPath = new Path(new File(cliParser.getOptionValue("workflow")).getCanonicalPath());
		} catch (IOException e) {
			e.printStackTrace();
			System.exit(-1);
		}
		workflowType = HiWayConfiguration.HIWAY_WORKFLOW_LANGUAGE_OPTS.valueOf(cliParser.getOptionValue("language",
				HiWayConfiguration.HIWAY_WORKFLOW_LANGUAGE_OPTS.cuneiform.toString()));

		clientTimeout = conf.getInt(HiWayConfiguration.HIWAY_AM_TIMEOUT, HiWayConfiguration.HIWAY_AM_TIMEOUT_DEFAULT) * 1000;

		return true;
	}

	/**
	 * Monitor the submitted application for completion. Kill application if time expires.
	 * 
	 * @param appId
	 *            Application Id of application to be monitored
	 * @return true if application completed successfully
	 * @throws YarnException
	 * @throws IOException
	 */
	private boolean monitorApplication(ApplicationId appId) throws YarnException, IOException {
		while (true) {
			// Check app status every 1 second.
			try {
				Thread.sleep(1000);
			} catch (InterruptedException e) {
				System.out.println("Thread sleep in monitoring loop interrupted");
			}

			// Get application report for the appId we are interested in
			ApplicationReport report = yarnClient.getApplicationReport(appId);

			YarnApplicationState state = report.getYarnApplicationState();
			FinalApplicationStatus dsStatus = report.getFinalApplicationStatus();
			if (YarnApplicationState.FINISHED == state) {
				if (FinalApplicationStatus.SUCCEEDED == dsStatus) {
					System.out.println("Application has completed successfully. Breaking monitoring loop");
					System.out.println(report.getDiagnostics());
					return true;
				}
				System.out.println("Application did finish unsuccessfully." + " YarnState=" + state.toString() + ", DSFinalStatus=" + dsStatus.toString()
						+ ". Breaking monitoring loop");

				return false;
			} else if (YarnApplicationState.KILLED == state || YarnApplicationState.FAILED == state) {
				System.out.println("Application did not finish." + " YarnState=" + state.toString() + ", DSFinalStatus=" + dsStatus.toString()
						+ ". Breaking monitoring loop");
				return false;
			}
			if (System.currentTimeMillis() > (clientStartTime + clientTimeout)) {
				System.out.println("Reached client specified timeout for application. Killing application");
				forceKillApplication(appId);
				return false;
			}
		}
	}

	/**
	 * Helper function to print out usage.
	 */
	private void printUsage() {
		new HelpFormatter().printHelp("Client", opts);
	}

	/**
	 * Main run function for the client.
	 * 
	 * @return true if application completed successfully.
	 * @throws IOException
	 * @throws YarnException
	 */
	public boolean run() throws IOException, YarnException {
		System.out.println("Running Client");
		yarnClient.start();

		YarnClusterMetrics clusterMetrics = yarnClient.getYarnClusterMetrics();
		System.out.println("Got Cluster metric info from ASM" + ", numNodeManagers=" + clusterMetrics.getNumNodeManagers());

		List<NodeReport> clusterNodeReports = yarnClient.getNodeReports(NodeState.RUNNING);
		System.out.println("Got Cluster node info from ASM");
		for (NodeReport node : clusterNodeReports) {
			System.out.println("Got node report from ASM for" + ", nodeId=" + node.getNodeId() + ", nodeAddress" + node.getHttpAddress() + ", nodeRackName"
					+ node.getRackName() + ", nodeNumContainers" + node.getNumContainers());
		}

		QueueInfo queueInfo = yarnClient.getQueueInfo(this.amQueue);
		System.out.println("Queue info" + ", queueName=" + queueInfo.getQueueName() + ", queueCurrentCapacity=" + queueInfo.getCurrentCapacity()
				+ ", queueMaxCapacity=" + queueInfo.getMaximumCapacity() + ", queueApplicationCount=" + queueInfo.getApplications().size()
				+ ", queueChildQueueCount=" + queueInfo.getChildQueues().size());

		List<QueueUserACLInfo> listAclInfo = yarnClient.getQueueAclsInfo();
		for (QueueUserACLInfo aclInfo : listAclInfo) {
			for (QueueACL userAcl : aclInfo.getUserAcls()) {
				System.out.println("User ACL Info for Queue" + ", queueName=" + aclInfo.getQueueName() + ", userAcl=" + userAcl.name());
			}
		}

		// Get a new application id
		YarnClientApplication app = yarnClient.createApplication();
		GetNewApplicationResponse appResponse = app.getNewApplicationResponse();

		// Get min/max resource capabilities from RM and change memory ask if needed
		int maxMem = appResponse.getMaximumResourceCapability().getMemory();
		System.out.println("Max mem capabililty of resources in this cluster " + maxMem);

		// A resource ask cannot exceed the max.
		if (amMemory > maxMem) {
			System.out.println("AM memory specified above max threshold of cluster. Using max value." + ", specified=" + amMemory + ", max=" + maxMem);
			amMemory = maxMem;
		}

		// set the application name
		ApplicationSubmissionContext appContext = app.getApplicationSubmissionContext();
		appContext.setApplicationType(conf.get(HiWayConfiguration.HIWAY_AM_APPLICATION_TYPE, HiWayConfiguration.HIWAY_AM_APPLICATION_TYPE_DEFAULT));
		appContext.setApplicationName("run " + workflowPath.getName() + " (type: " + workflowType.toString() + ")");
		ApplicationId appId = appContext.getApplicationId();
		String hdfsBaseDirectoryName = conf.get(HiWayConfiguration.HIWAY_AM_DIRECTORY_BASE, HiWayConfiguration.HIWAY_AM_DIRECTORY_BASE_DEFAULT);
		String hdfsSandboxDirectoryName = conf.get(HiWayConfiguration.HIWAY_AM_DIRECTORY_CACHE, HiWayConfiguration.HIWAY_AM_DIRECTORY_CACHE_DEFAULT);
		Path hdfsBaseDirectory = new Path(new Path(hdfs.getUri()), hdfsBaseDirectoryName);
		Data.setHdfsBaseDirectory(hdfsBaseDirectory);
		Path hdfsSandboxDirectory = new Path(hdfsBaseDirectory, hdfsSandboxDirectoryName);
		Path hdfsApplicationDirectory = new Path(hdfsSandboxDirectory, appId.toString());
		Data.setHdfsApplicationDirectory(hdfsApplicationDirectory);
		Data.setHdfs(hdfs);
		workflow = new Data(workflowPath);
		if (summaryPath != null)
			summary = new Data(summaryPath);

		// Set up the container launch context for the application master
		ContainerLaunchContext amContainer = Records.newRecord(ContainerLaunchContext.class);

		// Copy the application master jar to the filesystem
		workflow.stageOut();

		/* set the env variables to be setup in the env where the application master will be run */
		System.out.println("Set the environment for the application master");
		Map<String, String> env = new HashMap<>();

		StringBuilder classPathEnv = new StringBuilder(Environment.CLASSPATH.$()).append(File.pathSeparatorChar).append("./*");
		for (String c : conf.getStrings(YarnConfiguration.YARN_APPLICATION_CLASSPATH, YarnConfiguration.DEFAULT_YARN_APPLICATION_CLASSPATH)) {
			classPathEnv.append(':');
			classPathEnv.append(File.pathSeparatorChar);
			classPathEnv.append(c.trim());
		}

		if (conf.getBoolean(YarnConfiguration.IS_MINI_YARN_CLUSTER, false)) {
			classPathEnv.append(':');
			classPathEnv.append(System.getProperty("java.class.path"));
		}

		env.put("CLASSPATH", classPathEnv.toString());

		amContainer.setEnvironment(env);

		// Set the necessary command to execute the application master
		Vector<CharSequence> vargs = new Vector<>(30);

		// Set java executable command
		System.out.println("Setting up app master command");
		vargs.add(Environment.JAVA_HOME.$() + "/bin/java");
		// Set Xmx based on am memory size
		vargs.add("-Xmx" + amMemory + "m");
		// Set class name

		switch (workflowType) {
		case dax:
			vargs.add(HiWayConfiguration.HIWAY_WORKFLOW_LANGUAGE_DAX_CLASS);
			break;
		case log:
			vargs.add(HiWayConfiguration.HIWAY_WORKFLOW_LANGUAGE_LOG_CLASS);
			break;
		case galaxy:
			vargs.add(HiWayConfiguration.HIWAY_WORKFLOW_LANGUAGE_GALAXY_CLASS);
			break;
		default:
			vargs.add(HiWayConfiguration.HIWAY_WORKFLOW_LANGUAGE_CUNEIFORM_CLASS);
		}

		vargs.add("--workflow " + workflow.getName());
		if (summary != null) {
			vargs.add("--summary " + summary.getName());
		}
		vargs.add("--appid " + appId.toString());

		if (debugFlag) {
			vargs.add("--debug");
		}

		vargs.add("> >(tee AppMaster.stdout " + ApplicationConstants.LOG_DIR_EXPANSION_VAR + "/AppMaster.stdout)");
		vargs.add("2> >(tee AppMaster.stderr " + ApplicationConstants.LOG_DIR_EXPANSION_VAR + "/AppMaster.stderr >&2)");

		// Get final command
		StringBuilder command = new StringBuilder();
		for (CharSequence str : vargs) {
			command.append(str).append(" ");
		}

		System.out.println("Completed setting up app master command " + command.toString());
		List<String> commands = new ArrayList<>();
		commands.add(command.toString());
		amContainer.setCommands(commands);

		// Set up resource type requirements
		Resource capability = Records.newRecord(Resource.class);
		capability.setMemory(amMemory);
		appContext.setResource(capability);

		// Setup security tokens
		if (UserGroupInformation.isSecurityEnabled()) {
			Credentials credentials = new Credentials();
			String tokenRenewer = conf.get(YarnConfiguration.RM_PRINCIPAL);
			if (tokenRenewer == null || tokenRenewer.length() == 0) {
				throw new IOException("Can't get Master Kerberos principal for the RM to use as renewer");
			}

			// For now, only getting tokens for the default file-system.
			final Token<?> tokens[] = hdfs.addDelegationTokens(tokenRenewer, credentials);
			if (tokens != null) {
				for (Token<?> token : tokens) {
					System.out.println("Got dt for " + hdfs.getUri() + "; " + token);
				}
			}
			try (DataOutputBuffer dob = new DataOutputBuffer()) {
				credentials.writeTokenStorageToStream(dob);
				ByteBuffer fsTokens = ByteBuffer.wrap(dob.getData(), 0, dob.getLength());
				amContainer.setTokens(fsTokens);
			}
		}

		appContext.setAMContainerSpec(amContainer);

		// Set the priority for the application master
		Priority pri = Records.newRecord(Priority.class);
		pri.setPriority(amPriority);
		appContext.setPriority(pri);

		// Set the queue to which this application is to be submitted in the RM
		appContext.setQueue(amQueue);

		// Submit the application to the applications manager
		System.out.println("Submitting application to ASM");
		yarnClient.submitApplication(appContext);

		// Monitor the application
		boolean success = monitorApplication(appId);

		if (summary != null) {
			summary.stageIn();
		}

		return success;

	}

}
