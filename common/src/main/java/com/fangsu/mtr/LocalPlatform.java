package com.fangsu.mtr;

import org.mtr.core.data.Platform;

public class LocalPlatform {
    private final Platform raw;
    public final long id;

    public LocalPlatform(Platform raw) {
        this.raw = raw;
        this.id = raw != null ? raw.getId() : 0L;
    }

    public LocalPlatform() {
        this.raw = null;
        this.id = 0L;
    }

    public Platform getRaw() {
        return raw;
    }
}
