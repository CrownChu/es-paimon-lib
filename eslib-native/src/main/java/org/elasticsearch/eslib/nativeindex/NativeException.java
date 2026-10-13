/* Licensed under the Elastic License 2.0. */
package org.elasticsearch.eslib.nativeindex;

import java.io.IOException;

/** Native failures retain their typed code and never turn into an empty successful result. */
public final class NativeException extends IOException {
    private final int code;
    private final boolean retryable, mutationStarted, rebuildRequired;

    NativeException(
            int code,
            String operation,
            boolean retryable,
            boolean mutationStarted,
            boolean rebuildRequired) {
        super(operation + " failed with native code " + code);
        this.code = code;
        this.retryable = retryable;
        this.mutationStarted = mutationStarted;
        this.rebuildRequired = rebuildRequired;
    }

    public int code() {
        return code;
    }

    public boolean retryable() {
        return retryable;
    }

    public boolean mutationStarted() {
        return mutationStarted;
    }

    public boolean rebuildRequired() {
        return rebuildRequired;
    }
}
