package org.jenkinsci.plugins.parallel_test_executor;

import org.jenkinsci.plugins.scriptsecurity.sandbox.whitelists.Whitelisted;

import java.io.Serializable;
import java.util.Collections;
import java.util.List;

/**
 * A list of file name patterns to include or exclude
 */
public class InclusionPattern implements Serializable {
    @Whitelisted
    public List<String> getList() {
        return Collections.unmodifiableList(list);
    }

    private final List<String> list;

    InclusionPattern(List<String> list) {
        this.list = list;
    }

    @Override
    public String toString() {
        return "InclusionPattern{" +
                "list=" + list +
                '}';
    }
}
