package org.jenkinsci.plugins.parallel_test_executor.ParallelTestExecutor

import jenkins.model.Jenkins
import org.jenkinsci.plugins.parallel_test_executor.Parallelism;

def f = namespace(lib.FormTagLib)

f.entry(title:"Test job to run", field:"testJob") {
    f.textbox()
}
f.entry(title:"List of tests to run", field:"testList") {
    f.textbox(default: 'pass.lst')
}
// f.entry(title:"Exclusion file name in the test job", field:"patternFile") {
//     f.textbox()
// }
// f.entry(title:"Optional inclusion file name in the test job", field:"includesPatternFile") {
//     f.textbox()
// }
f.entry(title:"Degree of parallelism", field:"parallelism") {
    f.hetero_radio(field:"parallelism", descriptors:Jenkins.instance.getDescriptorList(Parallelism.class))
}
f.entry(title:"Path to yate test engine and tests", field:"yatePath") {
    f.textbox(default: '/Users/johannes/git/parallel-test-executor-plugin/work/yates-stuff/')
}
f.entry(title:"Default runtime for new tests (in seconds)", field:"defaultTime") {
    f.textbox(default: 0.01)
}
f.block {
    f.entry(title:'Parameters for test job', help:descriptor.getHelpFile('parameters')) {
        f.repeatableHeteroProperty(
            field:'parameters',
            hasHeader:'true',
            oneEach:"true",
            addCaption:'Add Parameters',
            capture:'configFor')
    }
}

f.advanced {
    f.entry(title:"Inclusion file name in the test job", field:"outputTestList") {
        f.textbox("pass.lst")
    }
	f.entry(title:"Test report directory in the test job", field:"testReportFiles") {
	    f.textbox(default: "statistics.xml")
	}
    f.entry(title:"Automatically archive JUnit test results", field:"archiveTestResults") {
        f.checkbox(default: true)
    }
}
