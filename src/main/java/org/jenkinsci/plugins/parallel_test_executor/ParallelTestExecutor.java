package org.jenkinsci.plugins.parallel_test_executor;

import com.google.common.collect.ImmutableSet;
import hudson.Extension;
import hudson.FilePath;
import hudson.Launcher;
import hudson.Util;
import hudson.model.*;
import hudson.plugins.parameterizedtrigger.*;
import hudson.tasks.BuildStepDescriptor;
import hudson.tasks.Builder;
import hudson.tasks.junit.ClassResult;
import hudson.tasks.junit.CaseResult;
import hudson.tasks.junit.SuiteResult;
import hudson.tasks.junit.JUnitResultArchiver;
import hudson.tasks.test.AbstractTestResultAction;
import hudson.tasks.test.TabulatedResult;
import hudson.tasks.test.TestResult;
import org.kohsuke.stapler.AncestorInPath;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.DataBoundSetter;
import org.kohsuke.stapler.QueryParameter;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.commons.io.Charsets;

import javax.annotation.CheckForNull;

/**
 * @author Kohsuke Kawaguchi
 */
public class ParallelTestExecutor extends Builder {
    public static final int NUMBER_OF_BUILDS_TO_SEARCH = 20;
    public static final ImmutableSet<Result> RESULTS_OF_BUILDS_TO_CONSIDER = ImmutableSet.of(Result.SUCCESS, Result.UNSTABLE);
    private Parallelism parallelism;

    private String testJob;
    private static String testList = "pass.lst"; //tk SHOULD NOT BE STATIC!
    private static String outputTestList = "pass.lst"; //tk SHOULD NOT BE STATIC!
    private String testReportFiles;
    private boolean doNotArchiveTestResults = false;
    private static String yatePath = "/Users/johannes/git/parallel-test-executor-plugin/work/yates-stuff/"; //tk SHOULD NOT BE STATIC?
    private static int defaultTime = 1; //tk SHOULD NOT BE STATIC?
    private List<AbstractBuildParameters> parameters;

    @DataBoundConstructor
    public ParallelTestExecutor(Parallelism parallelism, String testJob, String testList, String outputTestList, String testReportFiles, boolean archiveTestResults, List<AbstractBuildParameters> parameters, String yatePath, int defaultTime) {
        this.parallelism = parallelism;
        this.testJob = testJob;
        this.testList = testList;
        this.outputTestList = outputTestList;
        this.testReportFiles = testReportFiles;
        // Always send the YATEPATH as a parameter to the test slave
        if (parameters == null) { 
        	parameters = new ArrayList<AbstractBuildParameters>();
        }
        parameters.add(new PredefinedBuildParameters("YATEPATH="+yatePath));
        this.parameters = parameters;
        this.doNotArchiveTestResults = !archiveTestResults;
        this.yatePath = yatePath;
        this.defaultTime = defaultTime;
    }

    public Parallelism getParallelism() {
        return parallelism;
    }

    public String getTestJob() {
        return testJob;
    }

    public String getTestList() {
        return testList;
    }

    public String getOutputTestList() {
        return outputTestList;
    }

    public int getDefaultTime() {
        return defaultTime;
    }
    
    public String getTestReportFiles() {
        return testReportFiles;
    }

    public String getYatePath() {
    	return yatePath;
    }

    public List<AbstractBuildParameters> getParameters() {
        return parameters;
    }

    public boolean isArchiveTestResults() {
        return !doNotArchiveTestResults;
    }

    @DataBoundSetter
    public void setTestList(String testList) {
        this.testList = testList;
    }

    @DataBoundSetter
    public void setOutputTestList(String outputTestList) {
        this.outputTestList = outputTestList;
    }

    @DataBoundSetter
    public void setDefaultTime(int defaultTime) {
    	this.defaultTime = defaultTime;
    }

    /**
     * {@link org.jenkinsci.plugins.parallel_test_executor.TestClass}es are divided into multiple sets of roughly equal size.
     */
    static class Knapsack implements Comparable<Knapsack> {
        /**
         * Total duration of all {@link org.jenkinsci.plugins.parallel_test_executor.TestClass}es that are in this knapsack.
         */
        long total;

        void add(TestClass tc) {
            assert tc.knapsack == null;
            tc.knapsack = this;
            total += tc.duration;
        }

        public int compareTo(Knapsack that) {
            long l = this.total - that.total;
            if (l < 0) return -1;
            if (l > 0) return 1;
            return 0;
        }
    }

    @Override
    public boolean perform(AbstractBuild<?, ?> build, Launcher launcher, BuildListener listener) throws InterruptedException, IOException {
        FilePath dir = build.getWorkspace().child("test-splits");
        dir.deleteRecursive();
        List<InclusionExclusionPattern> splits = findTestSplits(parallelism, build, listener);
        for (int i = 0; i < splits.size(); i++) {
            InclusionExclusionPattern pattern = splits.get(i);
            OutputStream os = dir.child("split." + i + ".include.lst").write(); //tkt
            try {
                PrintWriter pw = new PrintWriter(new OutputStreamWriter(os, Charsets.UTF_8));
                for (String filePattern : pattern.getList()) {
                    pw.println(filePattern);
                }
                pw.close();
            } finally {
                os.close();
            }
        }

        createTriggerBuilder().perform(build, launcher, listener);

        if (isArchiveTestResults()) {
            tally(build, launcher, listener);
        }

        return true;
    }

    static List<InclusionExclusionPattern> findTestSplits(Parallelism parallelism, Run<?,?> build, TaskListener listener) {
        TestResult tr = findPreviousTestResult(build, listener);
        // if (tr == null) {
        //     listener.getLogger().println("No record available, so executing everything in one place");
        //     return Collections.singletonList(new InclusionExclusionPattern(Collections.<String>emptyList(), false));
        // } else {

        Map<String/*fully qualified class name*/, TestClass> data = new TreeMap<String, TestClass>();
        
        // Read input lst file
        List<String> names = readInputFile(testList);

        // Generate TestClasses from the input file first
    	TestClass dp;
        for (int i=0; i<names.size(); i++) {
        	dp = new TestClass(names.get(i), defaultTime * 1000); // convert to milliseconds
        	data.put(dp.className, dp);
        }


        collect(tr, data);

        // sort in the descending order of the duration
        List<TestClass> sorted = new ArrayList<TestClass>(data.values());
        Collections.sort(sorted);

        // degree of the parallelism. we need minimum 1
        final int n = Math.max(1, parallelism.calculate(sorted));

        List<Knapsack> knapsacks = new ArrayList<Knapsack>(n);
        for (int i = 0; i < n; i++)
            knapsacks.add(new Knapsack());

        /*
            This packing problem is a NP-complete problem, so we solve
            this simply by a greedy algorithm. We pack heavier items first,
            and the result should be of roughly equal size
         */
        PriorityQueue<Knapsack> q = new PriorityQueue<Knapsack>(knapsacks);
        for (TestClass d : sorted) {
            Knapsack k = q.poll();
            k.add(d);
            q.add(k);
        }

        long total = 0, min = Long.MAX_VALUE, max = Long.MIN_VALUE;
        for (Knapsack k : knapsacks) {
            total += k.total;
            max = Math.max(max, k.total);
            min = Math.min(min, k.total);
        }
        long average = total / n;
        long variance = 0;
        for (Knapsack k : knapsacks) {
            variance += pow(k.total - average);
        }
        variance /= n;
        long stddev = (long) Math.sqrt(variance);
        listener.getLogger().printf("%d test classes (%dms) divided into %d sets. Min=%dms, Average=%dms, Max=%dms, stddev=%dms\n",
                data.size(), total, n, min, average, max, stddev);

        List<InclusionExclusionPattern> r = new ArrayList<InclusionExclusionPattern>();
        for (int i = 0; i < n; i++) {
            Knapsack k = knapsacks.get(i);
            List<String> elements = new ArrayList<String>();
            r.add(new InclusionExclusionPattern(elements, true)); // true since we're only dealing with inclusion lists tk
            for (TestClass d : sorted) {
                if (d.knapsack == k) {
                	// String modifications, mostly hard coded, may need to be changed... tk
                	String lmnt = d.getSourceFileName(".exp");
                	String[] lmnts = lmnt.split("/");
                	lmnt = yatePath + lmnts[0] + ".test/" + lmnts[1];                    	
                	elements.add(lmnt);
                	//elements.add(yatePath + d.getSourceFileName(".exp")); // tkt
                }
            }
        }
        return r;
    }
    
    /**
     * Reads the values of input file
     */
    private static List<String> readInputFile(String filename) {
    	List<String> lines = new ArrayList<String>();
    	int lastSlash;
    	int secondLastSlash;
    	String testName;
    	String suiteName;
    	try {
    		BufferedReader br = new BufferedReader(new FileReader(yatePath+filename));
    		String line = br.readLine();
    		while (line != null) { //tkt
    			lastSlash = line.lastIndexOf('/');
    			secondLastSlash = line.lastIndexOf('/', lastSlash-1);
    			testName = line.substring(lastSlash+1, line.lastIndexOf('.'));
    			suiteName = line.substring(secondLastSlash+1, lastSlash);
    			suiteName = suiteName.substring(0, suiteName.lastIndexOf('.'));

    			lines.add(suiteName + '.' + testName);
    			line = br.readLine();
    		}
    		br.close();
    	} catch (Exception e) {
    		System.out.println("Couldn't read file: "+filename);
    		System.out.println(e.getMessage());
    	}
    	return lines;
    }

    /**
     * Collects all the test reports
     */
    private void tally(AbstractBuild<?, ?> build, Launcher launcher, BuildListener listener) throws IOException, InterruptedException {
        new JUnitResultArchiver("test-splits/reports/**/*.xml", false, null).perform(build, launcher, listener);
    }

    /**
     * Create {@link hudson.plugins.parameterizedtrigger.TriggerBuilder} for launching test jobs.
     */
    private TriggerBuilder createTriggerBuilder() {
        // to let the caller job do a clean up, don't let the failure in the test job early-terminate the build process
        // that's why the first argument is ABORTED.
        BlockingBehaviour blocking = new BlockingBehaviour(Result.ABORTED, Result.UNSTABLE, Result.FAILURE);
        final AtomicInteger iota = new AtomicInteger(0);

        List<AbstractBuildParameters> parameterList = new ArrayList<AbstractBuildParameters>();
        parameterList.add(
                // put a marker action that we look for to collect test reports
                new AbstractBuildParameters() {
                    @Override
                    public Action getAction(AbstractBuild<?, ?> build, TaskListener listener) throws IOException, InterruptedException, DontTriggerException {
                        return new TestCollector(build, ParallelTestExecutor.this, iota.incrementAndGet());
                    }
                });
        if (parameters != null) {
            parameterList.addAll(parameters);
        }

        // actual logic of child process triggering is left up to the parameterized build
        List<MultipleBinaryFileParameterFactory.ParameterBinding> parameterBindings = new ArrayList<MultipleBinaryFileParameterFactory.ParameterBinding>();
        parameterBindings.add(new MultipleBinaryFileParameterFactory.ParameterBinding(getOutputTestList(), "test-splits/split.*.include.lst")); //tktktktk
        MultipleBinaryFileParameterFactory factory = new MultipleBinaryFileParameterFactory(parameterBindings);
        BlockableBuildTriggerConfig config = new BlockableBuildTriggerConfig(
                testJob,
                blocking,
                Collections.<AbstractBuildParameterFactory>singletonList(factory),
                parameterList
        );

        return new TriggerBuilder(config);
    }


    private static long pow(long l) {
        return l * l;
    }

    /**
     * Recursive visits the structure inside {@link hudson.tasks.test.TestResult}.
     */
    static private void collect(TestResult r, Map<String, TestClass> data) {
    	if (r instanceof CaseResult) {
    		CaseResult cr = (CaseResult) r;
    		if (! cr.isSkipped()) {
    			TestClass dp = new TestClass(cr);
    			if (data.containsKey(dp.className)) {//tktk don't add classes not in inclusion list!
    				data.put(dp.className, dp); // adds or updates TestClass
    			}
    		}
    		return; // no need to go deeper
    	}
        if (r instanceof TabulatedResult) {
            TabulatedResult tr = (TabulatedResult) r;
            for (TestResult child : tr.getChildren()) {
                collect(child, data);
            }
        }
    }

    private static TestResult findPreviousTestResult(Run<?, ?> b, TaskListener listener) {
        for (int i = 0; i < NUMBER_OF_BUILDS_TO_SEARCH; i++) {// limit the search to a small number to avoid loading too much
            b = b.getPreviousBuild();
            if (b == null) break;
            if(!RESULTS_OF_BUILDS_TO_CONSIDER.contains(b.getResult())) continue;

            AbstractTestResultAction tra = b.getAction(AbstractTestResultAction.class);
            if (tra == null) continue;

            Object o = tra.getResult();
            if (o instanceof TestResult) {
                listener.getLogger().printf("Using build #%d as reference\n", b.getNumber());
                return (TestResult) o;
            }
        }
        return null;    // couldn't find it
    }

    @Extension
    public static class DescriptorImpl extends BuildStepDescriptor<Builder> {
        @Override
        public boolean isApplicable(Class<? extends AbstractProject> aClass) {
            return true;
        }

        public AutoCompletionCandidates doAutoCompleteTestJob(@QueryParameter String value, @AncestorInPath Item self, @AncestorInPath ItemGroup container) {
            return AutoCompletionCandidates.ofJobNames(AbstractProject.class, value, self, container);
        }

        @Override
        public String getDisplayName() {
            return "Parallel test job execution";
        }
    }
}
