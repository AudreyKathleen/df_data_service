package com.datafibers.flinknext;

import java.io.File;
import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import com.datafibers.model.DFJobPOPJ;

import org.apache.flink.annotation.Public;
import org.apache.flink.api.common.InvalidProgramException;
import org.apache.flink.api.common.JobExecutionResult;
import org.apache.flink.api.java.ExecutionEnvironment;
import org.apache.flink.client.program.JobWithJars;
import org.apache.flink.client.program.ProgramInvocationException;
import org.apache.flink.configuration.ConfigConstants;
import org.apache.flink.configuration.Configuration;
import org.apache.flink.configuration.JobManagerOptions;
import org.apache.flink.streaming.api.environment.StreamExecutionEnvironment;
import org.apache.flink.streaming.api.graph.StreamGraph;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * For Unit Test Only
 * This is customized remote stream flink environment derived from RemoteStreamEnvironment class in Flink
 * executeRemotely is tailored to accept DFPOPJ as parameter so that DFClusterClient.runWithDFObj can use it.
 */
@Public
public class DFRemoteStreamEnvironment extends StreamExecutionEnvironment {

    private static final Logger LOG = LoggerFactory.getLogger(DFRemoteStreamEnvironment.class);

    /** The hostname of the JobManager */
    private final String host;

    /** The port of the JobManager main actor system */
    private final int port;

    /** The configuration used to parametrize the client that connects to the remote cluster */
    private final Configuration clientConfiguration;

    /** The jar files that need to be attached to each job */
    private final List<URL> jarFiles;

    /** The classpaths that need to be attached to each job */
    private final List<URL> globalClasspaths;

    /**
     * Creates a new RemoteStreamEnvironment that points to the master
     * (JobManager) described by the given host name and port.
     *
     * @param host
     *            The host name or address of the master (JobManager), where the
     *            program should be executed.
     * @param port
     *            The port of the master (JobManager), where the program should
     *            be executed.
     * @param jarFiles
     *            The JAR files with code that needs to be shipped to the
     *            cluster. If the program uses user-defined functions,
     *            user-defined input formats, or any libraries, those must be
     *            provided in the JAR files.
     */
    public DFRemoteStreamEnvironment(String host, int port, String... jarFiles) {
        this(host, port, null, jarFiles);
    }

    /**
     * Creates a new RemoteStreamEnvironment that points to the master
     * (JobManager) described by the given host name and port.
     *
     * @param host
     *            The host name or address of the master (JobManager), where the
     *            program should be executed.
     * @param port
     *            The port of the master (JobManager), where the program should
     *            be executed.
     * @param clientConfiguration
     *            The configuration used to parametrize the client that connects to the
     *            remote cluster.
     * @param jarFiles
     *            The JAR files with code that needs to be shipped to the
     *            cluster. If the program uses user-defined functions,
     *            user-defined input formats, or any libraries, those must be
     *            provided in the JAR files.
     */
    public DFRemoteStreamEnvironment(String host, int port, Configuration clientConfiguration, String... jarFiles) {
        this(host, port, clientConfiguration, jarFiles, null);
    }

    /**
     * Creates a new RemoteStreamEnvironment that points to the master
     * (JobManager) described by the given host name and port.
     *
     * @param host
     *            The host name or address of the master (JobManager), where the
     *            program should be executed.
     * @param port
     *            The port of the master (JobManager), where the program should
     *            be executed.
     * @param clientConfiguration
     *            The configuration used to parametrize the client that connects to the
     *            remote cluster.
     * @param jarFiles
     *            The JAR files with code that needs to be shipped to the
     *            cluster. If the program uses user-defined functions,
     *            user-defined input formats, or any libraries, those must be
     *            provided in the JAR files.
     * @param globalClasspaths
     *            The paths of directories and JAR files that are added to each user code
     *            classloader on all nodes in the cluster. Note that the paths must specify a
     *            protocol (e.g. file://) and be accessible on all nodes (e.g. by means of a NFS share).
     *            The protocol must be supported by the {@link java.net.URLClassLoader}.
     */
    public DFRemoteStreamEnvironment(String host, int port, Configuration clientConfiguration, String[] jarFiles, URL[] globalClasspaths) {
        if (!ExecutionEnvironment.areExplicitEnvironmentsAllowed()) {
            throw new InvalidProgramException(
                    "The RemoteEnvironment cannot be used when submitting a program through a client, " +
                            "or running in a TestEnvironment context.");
        }

        if (host == null) {
            throw new NullPointerException("Host must not be null.");
        }
        if (port < 1 || port >= 0xffff) {
            throw new IllegalArgumentException("Port out of range");
        }

        this.host = host;
        this.port = port;
        this.clientConfiguration = clientConfiguration == null ? new Configuration() : clientConfiguration;
        this.jarFiles = new ArrayList<>(jarFiles.length);
        for (String jarFile : jarFiles) {
            try {
                URL jarFileUrl = new File(jarFile).getAbsoluteFile().toURI().toURL();
                this.jarFiles.add(jarFileUrl);
                JobWithJars.checkJarFile(jarFileUrl);
            } catch (MalformedURLException e) {
                throw new IllegalArgumentException("JAR file path is invalid '" + jarFile + "'", e);
            } catch (IOException e) {
                throw new RuntimeException("Problem with jar file " + jarFile, e);
            }
        }
        if (globalClasspaths == null) {
            this.globalClasspaths = Collections.emptyList();
        }
        else {
            this.globalClasspaths = Arrays.asList(globalClasspaths);
        }
    }

    public DFRemoteStreamEnvironment setParallelism(int parallelism) {
        if (parallelism < 1) {
            throw new IllegalArgumentException("parallelism must be at least one.");
        }
        super.getConfig().setParallelism(parallelism);
        return this;
    }

    @Override
    public JobExecutionResult execute(String jobName) throws ProgramInvocationException {
        StreamGraph streamGraph = getStreamGraph();
        streamGraph.setJobName(jobName);
        transformations.clear();
        return executeRemotely(streamGraph, jarFiles);
    }

    public JobExecutionResult executeWithDFObj(String jobName, DFJobPOPJ dfJobPOPJ) throws ProgramInvocationException {
        StreamGraph streamGraph = getStreamGraph();
        streamGraph.setJobName(jobName);
        transformations.clear();
        return executeRemotely(streamGraph, jarFiles, dfJobPOPJ);
    }

    /**
     * Executes the remote job.
     *
     * @param streamGraph
     *            Stream Graph to execute
     * @param jarFiles
     * 			  List of jar file URLs to ship to the cluster
     * @return The result of the job execution, containing elapsed time and accumulators.
     */
    protected JobExecutionResult executeRemotely(StreamGraph streamGraph, List<URL> jarFiles)
            throws ProgramInvocationException {
        return executeRemotely(streamGraph, jarFiles, null);
    }

    protected JobExecutionResult executeRemotely(StreamGraph streamGraph, List<URL> jarFiles,
                                                 DFJobPOPJ dfJobPOPJ) throws ProgramInvocationException {
        if (LOG.isInfoEnabled()) {
            LOG.info("Running remotely at {}:{}", host, port);
        }

        ClassLoader usercodeClassLoader = JobWithJars.buildUserCodeClassLoader(jarFiles, globalClasspaths,
                getClass().getClassLoader());

        Configuration configuration = new Configuration();
        configuration.addAll(this.clientConfiguration);

        configuration.setString(JobManagerOptions.ADDRESS, host);
        configuration.setInteger(JobManagerOptions.PORT, port);

        DFCusterClient client;
        try {
            client = new DFCusterClient(configuration);
            client.setPrintStatusDuringExecution(getConfig().isSysoutLoggingEnabled());
        }
        catch (Exception e) {
            throw new ProgramInvocationException("Cannot establish connection to JobManager: " + e.getMessage(), e);
        }

        try {
            return client.runWithDFObj(streamGraph, jarFiles, globalClasspaths, usercodeClassLoader, dfJobPOPJ).getJobExecutionResult();
        }
        catch (ProgramInvocationException e) {
            throw e;
        }
        catch (Exception e) {
            String term = e.getMessage() == null ? "." : (": " + e.getMessage());
            throw new ProgramInvocationException("The program execution failed" + term, e);
        }
        finally {
            try {
				client.shutdown();
			} catch (Exception e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			}
        }
    }

    @Override
    public String toString() {
        return "Remote Environment (" + this.host + ":" + this.port + " - parallelism = "
                + (getParallelism() == -1 ? "default" : getParallelism()) + ")";
    }

    /**
     * Gets the hostname of the master (JobManager), where the
     * program will be executed.
     *
     * @return The hostname of the master
     */
    public String getHost() {
        return host;
    }

    /**
     * Gets the port of the master (JobManager), where the
     * program will be executed.
     *
     * @return The port of the master
     */
    public int getPort() {
        return port;
    }


    public Configuration getClientConfiguration() {
        return clientConfiguration;
    }
}

