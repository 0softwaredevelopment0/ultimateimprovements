package com.ultimateimprovements.chat;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Guards the console-log matcher used by {@link PrivateMessageLogFilter}: it
 * must hide every direct-message command but leave unrelated commands (and
 * chat lines that merely mention "/msg") visible.
 */
class PrivateMessageLogFilterTest {

    private static boolean hidden(String line) {
        return PrivateMessageLogFilter.isPrivateCommand(line);
    }

    @Test
    void hidesDirectMessageCommands() {
        assertTrue(hidden("Steve issued server command: /msg Alex hello there"));
        assertTrue(hidden("Steve issued server command: /tell Alex hello"));
        assertTrue(hidden("Steve issued server command: /w Alex hello"));
        assertTrue(hidden("Steve issued server command: /whisper Alex hello"));
        assertTrue(hidden("Steve issued server command: /m Alex hello"));
        assertTrue(hidden("Steve issued server command: /t Alex hello"));
        assertTrue(hidden("Steve issued server command: /pm Alex hello"));
        assertTrue(hidden("Steve issued server command: /dm Alex hello"));
        assertTrue(hidden("Steve issued server command: /reply hello"));
        assertTrue(hidden("Steve issued server command: /r hello"));
        assertTrue(hidden("Steve issued server command: /msg"));
    }

    @Test
    void hidesNamespacedAndChatChannelCommands() {
        assertTrue(hidden("Steve issued server command: /minecraft:msg Alex hello"));
        assertTrue(hidden("Steve issued server command: /ui chatchnl private Alex"));
        assertTrue(hidden("Steve issued server command: /ultimateimprovements chatchnl private Alex"));
    }

    @Test
    void keepsOtherCommandsVisible() {
        assertFalse(hidden("Steve issued server command: /message Alex hello"));
        assertFalse(hidden("Steve issued server command: /mail Alex hello"));
        assertFalse(hidden("Steve issued server command: /warp spawn"));
        assertFalse(hidden("Steve issued server command: /ui chatchnl global"));
        assertFalse(hidden("Steve issued server command: /ui chatchnl privatex Alex"));
        assertFalse(hidden("Steve issued server command: /ui near"));
        assertFalse(hidden("<Steve> use /msg Alex to talk"));
        assertFalse(hidden(null));
    }
}
