package com.fangsu.ticketSystem;

public record FareInfo(FareType type, int value, int value2, int value3, String displayName) {

    public FareInfo(FareType type, int value, String displayName) {
        this(type, value, 0, 0, displayName);
    }

    public FareInfo(FareType type, int value) {
        this(type, value, 0, 0, "");
    }
}
