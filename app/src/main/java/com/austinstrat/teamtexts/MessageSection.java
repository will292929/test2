package com.austinstrat.teamtexts;

import java.util.ArrayList;
import java.util.List;

public final class MessageSection {
    public String name;
    public final List<String> options = new ArrayList<>();

    public MessageSection(String name) {
        this.name = name == null ? "" : name;
    }
}
