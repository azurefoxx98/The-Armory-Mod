package me.ladypaladra.thearmorymod.tailoring.commands;

import com.hypixel.hytale.server.core.command.system.basecommands.AbstractCommandCollection;

public final class ArmorTailoringBaseCommand extends AbstractCommandCollection {

    public static final String COMMAND_NAME = "armortailoring";

    public ArmorTailoringBaseCommand() {
        super(COMMAND_NAME, "Command to interact with armor tailoring.");
        this.requirePermission("armortailoring.admin");

        this.addSubCommand(new ArmorTailoringOpenCommand());
    }
}