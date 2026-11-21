package com.ultimateimprovements.mechanics.security.auth;

import com.ultimateimprovements.core.Main;
import com.ultimateimprovements.config.MessagesManager;
import com.ultimateimprovements.util.MessageUtil;
import com.ultimateimprovements.util.ConsoleLogger;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.NamespacedKey;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitRunnable;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Authentication logic: login, registration, password change, logout.
 * <p>
 * Player interaction:
 * <ul>
 *   <li>login / registration — the auth dialog (Custom Screen) with a password field,
 *       submitted through {@code AuthDialogHandler}</li>
 *   <li>{@code /ui auth logout} — self-logout, no password</li>
 *   <li>{@code /ui auth chgpass <nick> <new_password>} — admin password recovery</li>
 * </ul>
 * <p>
 * Orchestrates the interaction between AuthDatabase, AuthPlayerState,
 * AuthRateLimiter, AuthTimeoutManager and AuthConfig.
 */
public class AuthAuthenticator {

    private static AuthAuthenticator instance;
    private final AuthPlayerState playerState;
    private final AuthRateLimiter rateLimiter;
    private final AuthTimeoutManager timeoutManager;

    /** Fallback for {@code auth.twofa.banner} when the config has no list. */
    private static final List<String> DEFAULT_TWOFA_BANNER = List.of(
            "<gold>✦ <white>Two-Factor Authentication (2FA)",
            "<gray>━━━━━━━━━━━━━━━━━━━━━",
            "<yellow>Want to secure your account via GitHub?",
            "<white>1. Enter: <yellow>/ui auth 2fa setup <github_username>",
            "<white>2. On next login you will receive a GitHub authorization link.",
            "<gray>You can set up 2FA later with the same command.",
            "<gray>━━━━━━━━━━━━━━━━━━━━━");

    /**
     * Saves the player's state BEFORE the freeze, to restore it after authentication.
     * Without this, operators lose CREATIVE and allowFlight on every join.
     */
    private final Map<UUID, SavedPlayerState> savedStates = new ConcurrentHashMap<>();

    /**
     * A player.dat marker containing the complete pre-auth state. It lets the auth
     * freeze be rolled back after an abrupt process stop, when PlayerQuitEvent and
     * the in-memory savedStates map are unavailable.
     */
    private static final String PERSISTED_STATE_KEY = "auth_pending_restore";

    private record SavedPlayerState(GameMode gameMode, float walkSpeed, float flySpeed,
                                    boolean allowFlight, boolean flying, boolean invulnerable) {}

    private NamespacedKey persistedStateKey() {
        return new NamespacedKey(Main.getInstance(), PERSISTED_STATE_KEY);
    }

    private String serializeSavedState(SavedPlayerState state) {
        String raw = "1|" + state.gameMode.name() + "|" + state.walkSpeed + "|" + state.flySpeed
                + "|" + state.allowFlight + "|" + state.flying + "|" + state.invulnerable;
        return Base64.getEncoder().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    private SavedPlayerState deserializeSavedState(String encoded) {
        if (encoded == null || encoded.isBlank()) return null;
        try {
            String raw = new String(Base64.getDecoder().decode(encoded), StandardCharsets.UTF_8);
            String[] fields = raw.split("\\|", -1);
            if (fields.length != 7 || !"1".equals(fields[0])) return null;

            float walkSpeed = Float.parseFloat(fields[2]);
            float flySpeed = Float.parseFloat(fields[3]);
            if (!Float.isFinite(walkSpeed) || walkSpeed < -1.0f || walkSpeed > 1.0f
                    || !Float.isFinite(flySpeed) || flySpeed < -1.0f || flySpeed > 1.0f) {
                return null;
            }

            return new SavedPlayerState(
                    GameMode.valueOf(fields[1]),
                    walkSpeed,
                    flySpeed,
                    Boolean.parseBoolean(fields[4]),
                    Boolean.parseBoolean(fields[5]),
                    Boolean.parseBoolean(fields[6]));
        } catch (Exception e) {
            ConsoleLogger.warn("[Auth] Could not read persisted pre-auth state: " + e.getMessage());
            return null;
        }
    }

    private boolean hasPersistedSavedState(Player player) {
        return player.getPersistentDataContainer().has(persistedStateKey(), PersistentDataType.STRING);
    }

    private SavedPlayerState readPersistedSavedState(Player player) {
        String encoded = player.getPersistentDataContainer().get(persistedStateKey(), PersistentDataType.STRING);
        return deserializeSavedState(encoded);
    }

    private void persistSavedState(Player player, SavedPlayerState state) {
        player.getPersistentDataContainer().set(
                persistedStateKey(), PersistentDataType.STRING, serializeSavedState(state));
        // Persist the recovery marker before granting temporary flight. If the
        // process stops unexpectedly, the next join can restore the pre-auth state.
        try {
            player.saveData();
        } catch (Throwable t) {
            ConsoleLogger.warn("[Auth] Could not persist pre-auth state for " + player.getName()
                    + ": " + t.getMessage());
        }
    }

    private void clearPersistedSavedState(Player player) {
        player.getPersistentDataContainer().remove(persistedStateKey());
    }

    private void savePlayerData(Player player) {
        try {
            player.saveData();
        } catch (Throwable t) {
            ConsoleLogger.warn("[Auth] Could not save restored player data for " + player.getName()
                    + ": " + t.getMessage());
        }
    }

    private void restoreSavedState(Player player, SavedPlayerState saved) {
        player.setGameMode(saved.gameMode);
        player.setWalkSpeed(saved.walkSpeed);
        player.setFlySpeed(saved.flySpeed);
        player.setAllowFlight(saved.allowFlight);
        player.setFlying(saved.flying);
        player.setInvulnerable(saved.invulnerable);
    }

    private void restoreSafeDefaults(Player player) {
        // A corrupt/missing recovery payload must never leave a temporary flight
        // grant or a frozen player behind.
        player.setGameMode(GameMode.SURVIVAL);
        player.setWalkSpeed(0.2f);
        player.setFlySpeed(0.1f);
        player.setAllowFlight(false);
        player.setFlying(false);
        player.setInvulnerable(false);
    }

    public AuthAuthenticator(AuthPlayerState playerState, AuthRateLimiter rateLimiter, AuthTimeoutManager timeoutManager) {
        this.playerState = playerState;
        this.rateLimiter = rateLimiter;
        this.timeoutManager = timeoutManager;
        instance = this;
    }

    public static AuthAuthenticator getInstance() {
        return instance;
    }

    // =========================
    // HANDLE JOIN
    // =========================
    public void handleJoin(Player player) {
        if (!AuthConfig.isEnabled()) return;

        UUID uuid = player.getUniqueId();

        // Always require a real client address, even when IP-bound sessions are
        // disabled. Never substitute the backend socket/proxy address.
        String currentIp = getPlayerIp(player);
        if (currentIp == null) {
            kickInvalidClientIp(player);
            return;
        }

        if (playerState.isAuthenticated(uuid)) return;

        // Fail closed: with no auth table an account can neither be read nor
        // verified, so letting the player through would disable the whole gate.
        if (!AuthDatabase.isTableReady()) {
            ConsoleLogger.warn("[Auth] DB not ready — refusing auth for " + player.getName());
            kickStorageUnavailable(player);
            return;
        }

        Boolean registeredOrNull = AuthDatabase.isRegisteredOrNull(uuid);
        if (registeredOrNull == null) {
            ConsoleLogger.warn("[Auth] Registration read failed — refusing auth for " + player.getName());
            kickStorageUnavailable(player);
            return;
        }
        boolean registered = registeredOrNull;

        if (registered) {
            if (AuthConfig.isIpCheckEnabled()) {
                String lastIp = AuthDatabase.getLastIpOrNull(uuid);
                if (lastIp == null) {
                    // Cannot compare the session IP — fail closed instead of skipping
                    // the binding on a transient read failure.
                    ConsoleLogger.warn("[Auth] IP read failed — refusing auth for " + player.getName());
                    kickStorageUnavailable(player);
                    return;
                }

                if (lastIp.isBlank()) {
                    // A session without a previously stored client IP is not bound
                    // to this connection. Require the password instead of restoring it.
                    ConsoleLogger.warn("[Auth] Stored client IP missing for " + player.getName()
                            + " — refusing session restoration.");
                    AuthDatabase.resetAuth(uuid);
                } else if (!lastIp.equals(currentIp)) {
                    ConsoleLogger.info(
                            "[Auth] Player " + player.getName() + " client IP changed: " + lastIp
                                    + " → " + currentIp + " — session reset.");
                    String ipMsg = AuthConfig.getMessage("ip_changed",
                            "<yellow>✦</yellow> <gray>Your IP address has changed. Please log in again.</gray>");
                    player.sendMessage(MessageUtil.parse(ipMsg));
                    AuthDatabase.resetAuth(uuid);
                } else if (AuthDatabase.hasValidSession(uuid, AuthConfig.getSessionDurationMs())) {
                    if (!storeClientIp(player, currentIp)) return;
                    playerState.setAuthenticated(uuid);
                    ConsoleLogger.info(
                            "[Auth] Player " + player.getName() + " auto-authenticated (session + client IP match).");
                    return;
                }
            } else if (AuthDatabase.hasValidSession(uuid, AuthConfig.getSessionDurationMs())) {
                if (!storeClientIp(player, currentIp)) return;
                playerState.setAuthenticated(uuid);
                ConsoleLogger.info(
                        "[Auth] Player " + player.getName() + " auto-authenticated (session, IP check disabled).");
                return;
            }
        }

        // Save the final registered state for use in the Runnable
        final boolean isRegistered = registered;

        // Freeze the player (light freeze: can't move/interact until login)
        playerState.setPendingAuth(uuid);
        freezePlayer(player);
        timeoutManager.startLoginTimeout(player);

        // Dialog-based prompt (Custom Screen) — open the dialog with a delay
        new BukkitRunnable() {
            @Override
            public void run() {
                if (!player.isOnline()) return;
                if (playerState.isAuthenticated(uuid)) return;
                // Open a Custom Screen instead of chat
                AuthDialogScreen.open(player, isRegistered);
                // The client owns the dialog: if it never shows up (or the player closes
                // it), AuthListener re-sends it on the next blocked action — tell the
                // player right away what to do in that case.
                AuthPrompt.sendJoinHint(player);
            }
        }.runTaskLater(Main.getInstance(), 5L);
    }

    // =========================
    // HANDLE PASSWORD SUBMIT (login/register — password comes from the auth dialog)
    //
    // ⚠ Argon2id (32MB memory, 2 iterations) runs on an async thread
    // to avoid freezing the server for 1-2 seconds on every login.
    // =========================
    public void handlePasswordSubmit(Player player, String password) {
        UUID uuid = player.getUniqueId();

        if (playerState.isAuthenticated(uuid)) {
            AuthPrompt.removePlayer(uuid);
            player.sendMessage(MessageUtil.parse(MessagesManager.getString(
                    "auth.messages.already_authenticated",
                    "<gold>✦</gold> <white>You are already logged in!</white>")));
            return;
        }

        // A missing/invalid forwarded client address must never reach password,
        // registration, or rate-limit handling.
        String playerIp = getPlayerIp(player);
        if (playerIp == null) {
            kickInvalidClientIp(player);
            return;
        }

        if (!rateLimiter.checkCooldown(player)) {
            // The client already closed the dialog on submit, and the attempt was
            // rejected — nothing would ever re-open the screen, leaving the player
            // frozen with every action blocked. Re-open it when the lock expires.
            AuthPrompt.openLater(player, rateLimiter.getRemainingMs(player) + 500L);
            return;
        }
        // Serialize verifications per account/IP. The cooldown is applied only after
        // an error, so without this reservation a burst of auth_submit clicks all
        // passed it and started parallel Argon2 checks before the first result.
        // Duplicates are dropped silently: the first check is already in flight and
        // re-opening here would spawn one screen per extra packet.
        if (!rateLimiter.tryBeginAttempt(player)) return;

        // Argon2id on an async thread — prevents server freezes
        Bukkit.getScheduler().runTaskAsynchronously(Main.getInstance(), () -> {
            try {
                Boolean registeredOrNull = AuthDatabase.isRegisteredOrNull(uuid);
                if (registeredOrNull == null) {
                    Bukkit.getScheduler().runTask(Main.getInstance(), () -> {
                        if (!player.isOnline()) return;
                        player.sendMessage(MessageUtil.parse(MessagesManager.getString(
                                "auth.messages.auth_check_error",
                                "<red>❌ Error checking password! Please try again.</red>")));
                        sendChatPromptAfterError(player);
                    });
                    return;
                }
                boolean registered = registeredOrNull;

                if (registered) {
                    boolean passwordValid = AuthDatabase.checkPassword(uuid, password);
                    Bukkit.getScheduler().runTask(Main.getInstance(), () -> {
                        if (!player.isOnline()) return;
                        if (passwordValid) {
                            handleLoginSuccess(player, uuid, playerIp);
                        } else {
                            handleWrongPassword(player, uuid);
                        }
                    });
                } else {
                    // Password policy (length, character classes, common-password list).
                    // Pure string work — safe on the async thread; only sending stays on main.
                    final String policyError = PasswordPolicy.validate(password);
                    if (policyError != null) {
                        Bukkit.getScheduler().runTask(Main.getInstance(), () -> {
                            if (!player.isOnline()) return;
                            player.sendMessage(MessageUtil.parse(policyError));
                            player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 0.3f, 0.8f);
                            sendChatPromptAfterError(player);
                        });
                        return;
                    }

                    // Check the per-IP account limit
                    if (!playerIp.isEmpty()) {
                        int maxAccounts = AuthConfig.getMaxAccountsPerIp();
                        if (maxAccounts > 0) {
                            int currentCount = AuthDatabase.countAccountsByIp(playerIp);
                            if (currentCount >= maxAccounts) {
                                String msg = AuthConfig.getMessage("max_accounts_per_ip",
                                        "<red>❌</red> <yellow>%count%</yellow> <red>accounts are already registered from your IP address!</red>\n" +
                                        "<white>Maximum:</white> <yellow>%limit%</yellow> <white>accounts per IP.</white>")
                                        .replace("%count%", String.valueOf(currentCount))
                                        .replace("%limit%", String.valueOf(maxAccounts));
                                final String finalMsg = msg;
                                Bukkit.getScheduler().runTask(Main.getInstance(), () -> {
                                    if (!player.isOnline()) return;
                                    player.sendMessage("");
                                    player.sendMessage(MessageUtil.parse(finalMsg));
                                    player.sendMessage("");
                                    player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 0.3f, 0.8f);
                                    sendChatPromptAfterError(player);
                                });
                                return;
                            }
                        }
                    }

                    // hashArgon2 on an async thread (32MB memory)
                    boolean created = AuthDatabase.register(uuid, password, playerIp);
                    if (!created) {
                        // The row already exists (a concurrent submit or a previous
                        // session) or the write failed. Never certify an account
                        // that was not actually created.
                        Bukkit.getScheduler().runTask(Main.getInstance(), () -> {
                            if (!player.isOnline()) return;
                            player.sendMessage("");
                            player.sendMessage(MessageUtil.parse(MessagesManager.getString(
                                    "auth.messages.register_conflict",
                                    "<red>❌ Could not create the account — it is already registered.</red>\n"
                                            + "<gray>Reconnect and log in with your password.</gray>")));
                            player.sendMessage("");
                            player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 0.3f, 0.8f);
                            sendChatPromptAfterError(player);
                        });
                        return;
                    }

                    Bukkit.getScheduler().runTask(Main.getInstance(), () -> {
                        if (!player.isOnline()) return;
                        authenticatePlayer(player, MessagesManager.getString("auth.messages.success_register",
                                "<green>✔ Registration successful!</green>"));

                        // Suggest 2FA setup
                        player.sendMessage("");
                        for (String line : MessagesManager.getStringList("auth.twofa.banner", DEFAULT_TWOFA_BANNER)) {
                            player.sendMessage(MessageUtil.parse(line));
                        }
                        player.sendMessage("");
                    });
                }
            } catch (Exception e) {
                Bukkit.getScheduler().runTask(Main.getInstance(), () -> {
                    if (player.isOnline()) {
                        player.sendMessage(MessageUtil.parse(MessagesManager.getString(
                                "auth.messages.auth_check_error",
                                "<red>❌ Error checking password! Please try again.</red>")));
                        sendChatPromptAfterError(player);
                    }
                });
                Main.getInstance().getLogger().log(java.util.logging.Level.SEVERE, "[Auth] Async auth error", e);
            } finally {
                // Release the in-flight reservation on the main thread, after the
                // queued result task, so a following submit sees the penalty.
                try {
                    Bukkit.getScheduler().runTask(Main.getInstance(), () -> rateLimiter.finishAttempt(player));
                } catch (Throwable shutdown) {
                    rateLimiter.finishAttempt(player);
                }
            }
        });
    }

    /**
     * Sends the player a repeat prompt after a wrong password or other error:
     * re-opens the auth screen once the client has finished closing the old one.
     */
    private void sendChatPromptAfterError(Player player) {
        // The client closes the dialog on submit. Re-open it after the error message
        // without showing the generic "Action blocked" hint for this expected flow.
        AuthPrompt.openLater(player, 1000L);
    }

    // =========================
    // LOGIN SUCCESS (called from the main thread after the async password check)
    // =========================
    private void handleLoginSuccess(Player player, UUID uuid, String playerIp) {
        if (playerIp == null || playerIp.isBlank()) {
            kickInvalidClientIp(player);
            return;
        }
        if (AuthConfig.isIpCheckEnabled()) {
            String storedIp = AuthDatabase.getLastIpOrNull(uuid);
            if (storedIp == null) {
                ConsoleLogger.warn("[Auth] IP read failed after password verification for " + player.getName());
                kickStorageUnavailable(player);
                return;
            }
            if (!storedIp.isBlank() && !storedIp.equals(playerIp)) {
                // Do not update the stored IP until the full login flow (including
                // GitHub 2FA, if enabled) has completed.
                ConsoleLogger.info(
                        "[Auth] Player " + player.getName() + " client IP changed: " + storedIp + " → "
                                + playerIp + " — password verified; binding after full authentication.");
            }
        }

        // The password is verified — clear the persistent wrong-password counter.
        // NOTE: last_login is written only after FULL authentication (inside
        // authenticatePlayer). Writing it here made a reconnect within the session
        // window skip the 2FA challenge entirely.
        AuthDatabase.resetFailedAttempts(uuid);

        // If 2FA is enabled — start a challenge instead of full authentication
        if (AuthConfig.isGithub2FAEnabled()) {
            Auth2FA.TwoFAState state = Auth2FA.getState(uuid);
            if (state == Auth2FA.TwoFAState.ERROR) {
                // Fail closed: an unreadable 2FA row must not downgrade the account.
                ConsoleLogger.warn("[Auth] 2FA state unreadable for " + player.getName() + " — refusing login.");
                kickTwoFactorUnavailable(player, "storage_error");
                return;
            }
            if (state == Auth2FA.TwoFAState.ENABLED) {
                start2FAChallenge(player);
                return;
            }
        }

        authenticatePlayer(player, MessagesManager.getString("auth.messages.success_login",
                "<green>✔ You have successfully logged in!</green>"));
    }

    // =========================
    // 2FA CHALLENGE — confirmation via GitHub OAuth
    // =========================
    public void start2FAChallenge(Player player) {
        UUID uuid = player.getUniqueId();

        if (!AuthConfig.isGithub2FAEnabled()) {
            authenticatePlayer(player, MessagesManager.getString("auth.twofa.login_2fa_disabled",
                    "<green>✅</green> <white>Logged in (GitHub 2FA is disabled in the config).</white>"));
            return;
        }

        Auth2FA.TwoFAState state = Auth2FA.getState(uuid);
        if (state == Auth2FA.TwoFAState.ERROR) {
            // Fail closed — see handleLoginSuccess.
            ConsoleLogger.warn("[Auth2FA] 2FA state unreadable for " + player.getName() + " — refusing login.");
            kickTwoFactorUnavailable(player, "storage_error");
            return;
        }
        if (state == Auth2FA.TwoFAState.DISABLED) {
            authenticatePlayer(player, MessagesManager.getString("auth.twofa.login_2fa_disabled",
                    "<green>✅</green> <white>Logged in (GitHub 2FA is disabled).</white>"));
            return;
        }

        String githubUsername = Auth2FA.getGithubUsername(uuid);
        if (githubUsername == null || githubUsername.isBlank()) {
            // 2FA is ON but the binding is missing/unreadable. Never delete the
            // protection and let the player in: that used to turn a broken row (or
            // a DB error) into a full 2FA bypass. Only /ui auth 2fa disable removes it.
            ConsoleLogger.warn("[Auth2FA] 2FA enabled but no GitHub account linked for "
                    + player.getName() + " — refusing login.");
            kickTwoFactorUnavailable(player, "binding_missing");
            return;
        }

        String playerName = player.getName();

        // Remove the global login timeout — the 2FA challenge has its own limit (5 minutes),
        // otherwise the player would be kicked via auth.login_timeout_seconds mid-wait.
        timeoutManager.cancelLoginTimeout(uuid);

        // Start the challenge — a one-time state token, 5 minutes
        String requestId = Auth2FA.getInstance().sendConfirmation(uuid, playerName);
        String authUrl = Auth2FA.getInstance().getAuthUrl(uuid);
        if (requestId == null || authUrl == null) {
            player.sendMessage(MessageUtil.parse(MessagesManager.getString("auth.twofa.not_configured",
                    "<red>❌ GitHub 2FA is not configured! Contact an administrator.</red>")));
            // Don't unfreeze the player — they stay pendingAuth (all actions blocked),
            // and re-arm the timeout so the player gets kicked via login_timeout_seconds.
            timeoutManager.startLoginTimeout(player);
            return;
        }

        // Dialog-first: the player gets a window with a button that opens GitHub.
        // The plain chat line keeps the same link as a fallback — a client that drops
        // the dialog still has a way to authorize without reconnecting.
        AuthDialogs.openGithub2FA(player, githubUsername, authUrl);
        player.sendMessage(MessageUtil.parse(MessagesManager.getString("auth.messages.twofa_chat_fallback",
                "<gray>No 2FA window? Open the link:</gray> <blue><u>%url%</u></blue>")
                .replace("%url%", authUrl)));

        ConsoleLogger.info("[Auth2FA] GitHub challenge started for " + playerName
                + " (github: " + githubUsername + ", state: " + requestId + ")");

        // Start polling — check the status every 20 ticks (1 second)
        // Usually the player authenticates earlier via the callback (completeGithubAuth),
        // polling is a fallback path (e.g. if the player closed the page and reopened it).
        new BukkitRunnable() {
            int ticks = 0;
            final int maxTicks = 20 * 300; // 5 minutes max

            @Override
            public void run() {
                if (!player.isOnline()) {
                    Auth2FA.getInstance().clearPending(uuid);
                    cancel();
                    return;
                }

                if (playerState.isAuthenticated(uuid)) {
                    cancel();
                    return;
                }

                ticks += 20;
                if (ticks > maxTicks) {
                    // Timeout: remove the challenge and re-arm the global login timeout
                    // so the player doesn't stay frozen forever (they get kicked).
                    Auth2FA.getInstance().clearPending(uuid);
                    player.sendMessage(MessageUtil.parse(MessagesManager.getString("auth.twofa.timeout",
                            "<red>❌ 2FA timeout! Reconnect to get a new login window.</red>")));
                    timeoutManager.startLoginTimeout(player);
                    cancel();
                    return;
                }

                // Check the status (on an async thread to not block the server)
                Bukkit.getScheduler().runTaskAsynchronously(Main.getInstance(), () -> {
                    String status = Auth2FA.getInstance().checkConfirmation(uuid);

                    Bukkit.getScheduler().runTask(Main.getInstance(), () -> {
                        if (!player.isOnline()) return;
                        if (playerState.isAuthenticated(uuid)) return;

                        if ("approved".equals(status)) {
                            Auth2FA.getInstance().clearPending(uuid);
                            authenticatePlayer(player, MessagesManager.getString("auth.twofa.approved_callback",
                                    "<green>✅</green> <white>GitHub authorization confirmed! Welcome.</white>"));
                            cancel();
                        } else if ("timeout".equals(status) || "not_found".equals(status)) {
                            Auth2FA.getInstance().clearPending(uuid);
                            player.sendMessage(MessageUtil.parse(MessagesManager.getString("auth.twofa.error",
                                    "<red>❌ 2FA error! Reconnect to get a new login window.</red>")));
                            cancel();
                        }
                    });
                });
            }
        }.runTaskTimer(Main.getInstance(), 20L, 20L); // first after 1 sec, then every second
    }

    /**
     * Completes a player's GitHub authentication (called from the main thread from an HTTP callback).
     * If the player is online and waiting for 2FA — authenticate and refresh the session (1 hour).
     */
    public void completeGithubAuth(UUID uuid) {
        Player player = Bukkit.getPlayer(uuid);
        if (player == null || !player.isOnline()) return;
        if (playerState.isAuthenticated(uuid)) return;
        if (Auth2FA.getInstance() == null || !Auth2FA.getInstance().hasPendingConfirmation(uuid)) return;

        Auth2FA.getInstance().clearPending(uuid);
        // authenticatePlayer stamps last_login — only a completed 2FA may extend the session.
        authenticatePlayer(player, MessagesManager.getString("auth.twofa.approved_complete",
                "<green>✅</green> <white>GitHub authorization successful! Welcome.</white>"));
    }

    // =========================
    // WRONG PASSWORD
    // =========================
    private void handleWrongPassword(Player player, UUID uuid) {
        // The counter is persisted first: the lockout grows with every consecutive
        // failure (auth.lockout.*), so it has to know the attempt number, and a
        // reconnect (or restart) must not reset it.
        int attempts = AuthDatabase.incrementFailedAttempts(uuid);
        // Wrong password → lock the account AND the source IP.
        rateLimiter.applyPenalty(player, attempts);
        int maxWrong = AuthConfig.getMaxWrongAttempts();
        int remaining = maxWrong - attempts;

        if (attempts >= maxWrong) {
            timeoutManager.cancelLoginTimeout(uuid);
            // Close the dialog before kicking
            AuthDialogScreen.close(player);
            String kickMsg = MessagesManager.getString("auth.admin.kick_too_many_attempts",
                    "<red>❌ Too many incorrect attempts!</red>\n<gray>You entered the wrong password %attempts% times.</gray>")
                    .replace("%attempts%", String.valueOf(attempts));
            player.kick(MessageUtil.parse(kickMsg));
            return;
        }

        player.sendMessage(MessageUtil.parse(MessagesManager.getString(
                "auth.messages.wrong_password_remaining",
                "<red>❌ Incorrect password! Remaining attempts: </red><yellow>%remaining%</yellow>")
                .replace("%remaining%", String.valueOf(remaining))));
        // Re-open dialog with delay so player can read error
        sendChatPromptAfterError(player);
    }

    // =========================
    // AUTHENTICATE PLAYER (called from the main thread)
    // =========================
    private void authenticatePlayer(Player player, String message) {
        UUID uuid = player.getUniqueId();

        // Persist a validated client address before granting authenticated state.
        if (!ensureClientIpStored(player)) return;
        playerState.setAuthenticated(uuid);

        timeoutManager.cancelLoginTimeout(uuid);
        // Full authentication reached (password + 2FA when enabled): only now may
        // the session window start. This is the write that handleJoin trusts.
        AuthDatabase.updateLastLogin(uuid);
        AuthDatabase.resetFailedAttempts(uuid);
        // The auth screen is gone for good — drop the prompt bookkeeping.
        AuthPrompt.removePlayer(uuid);

        unfreezePlayer(player);

        player.sendMessage("");
        player.sendMessage(MessageUtil.parse(message));
        player.sendMessage(MessageUtil.parse(MessagesManager.getString(
                "auth.messages.session_active",
                "<gray>Enjoy your game! Session active for 1 hour.</gray>")));
        player.sendMessage("");

        ConsoleLogger.info("[Auth] Player " + player.getName() + " authenticated.");
    }

    // =========================
    // SELF CHANGE PASSWORD (already-authenticated player only)
    // hashArgon2 is called on an async thread
    // =========================
    private void handleSelfChangePassword(Player player, String newPassword) {
        UUID uuid = player.getUniqueId();

        if (!playerState.isAuthenticated(uuid)) {
            player.sendMessage(MessageUtil.parse(MessagesManager.getString(
                    "auth.admin.not_authenticated",
                    "<red>❌ You are not logged in!</red>")));
            return;
        }

        String policyError = PasswordPolicy.validate(newPassword);
        if (policyError != null) {
            player.sendMessage(MessageUtil.parse(policyError));
            return;
        }

        // hashArgon2 on an async thread (32MB memory)
        Bukkit.getScheduler().runTaskAsynchronously(Main.getInstance(), () -> {
            try {
                boolean changed = AuthDatabase.changePasswordSelf(uuid, newPassword);

                Bukkit.getScheduler().runTask(Main.getInstance(), () -> {
                    if (!player.isOnline()) return;

                    if (!changed) {
                        player.sendMessage(MessageUtil.parse(MessagesManager.getString(
                                "auth.messages.change_password_error",
                                "<red>❌ Password change error! Please try again.</red>")));
                        return;
                    }

                    if (!ensureClientIpStored(player)) return;
                    playerState.setAuthenticated(uuid);
                    AuthDatabase.resetFailedAttempts(uuid);

                    unfreezePlayer(player);

                    player.sendMessage("");
                    player.sendMessage(MessageUtil.parse(MessagesManager.getString(
                            "auth.messages.password_changed",
                            "<green>✔</green> <white>Password successfully changed!</white>")));
                    player.sendMessage(MessageUtil.parse(MessagesManager.getString(
                            "auth.messages.session_active",
                            "<gray>Enjoy your game! Session active for 1 hour.</gray>")));
                    player.sendMessage("");

                    ConsoleLogger.info("[Auth] Player " + player.getName() + " changed password.");
                });
            } catch (Exception e) {
                Bukkit.getScheduler().runTask(Main.getInstance(), () -> {
                    if (player.isOnline()) {
                        player.sendMessage(MessageUtil.parse(MessagesManager.getString(
                                "auth.messages.change_password_error",
                                "<red>❌ Password change error! Please try again.</red>")));
                    }
                });
                Main.getInstance().getLogger().log(java.util.logging.Level.SEVERE, "[Auth] Async change password error", e);
            }
        });
    }

    /**
     * Change password via chat command `/ui auth chgpass <old> <new>`.
     * Verifies old password asynchronously, then re-hashes new password.
     */
    public void handleSelfChangePassword(Player player, String oldPassword, String newPassword) {
        UUID uuid = player.getUniqueId();

        // Defence in depth: a crafted custom-click packet must not reach the
        // password change of a player who has not passed authentication yet.
        if (!playerState.isAuthenticated(uuid)) {
            player.sendMessage(MessageUtil.parse(MessagesManager.getString(
                    "auth.admin.not_authenticated",
                    "<red>❌ You are not logged in!</red>")));
            return;
        }

        String policyError = PasswordPolicy.validate(newPassword);
        if (policyError != null) {
            player.sendMessage(MessageUtil.parse(policyError));
            return;
        }
        if (oldPassword == null || oldPassword.isEmpty()) {
            player.sendMessage(MessageUtil.parse(MessagesManager.getString(
                    "auth.messages.current_password_required",
                    "<red>❌ Enter your current password in the password change window.</red>")));
            return;
        }

        if (!rateLimiter.checkCooldown(player)) return;
        if (!rateLimiter.tryBeginAttempt(player)) return;

        Bukkit.getScheduler().runTaskAsynchronously(Main.getInstance(), () -> {
            try {
                boolean validOld = AuthDatabase.checkPassword(uuid, oldPassword);
                Bukkit.getScheduler().runTask(Main.getInstance(), () -> {
                    if (!player.isOnline()) return;
                    if (!validOld) {
                        int attempts = AuthDatabase.incrementFailedAttempts(uuid);
                        rateLimiter.applyPenalty(player, attempts);
                        player.sendMessage(MessageUtil.parse(MessagesManager.getString(
                                "auth.messages.wrong_password",
                                "<red>❌ Current password is incorrect!</red>")));
                        player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 0.3f, 0.8f);
                        return;
                    }
                    // Re-use the existing async hash+update flow
                    handleSelfChangePassword(player, newPassword);
                });
            } catch (Exception e) {
                Main.getInstance().getLogger().log(java.util.logging.Level.SEVERE, "[Auth] Async chgpass verify error", e);
            } finally {
                try {
                    Bukkit.getScheduler().runTask(Main.getInstance(), () -> rateLimiter.finishAttempt(player));
                } catch (Throwable shutdown) {
                    rateLimiter.finishAttempt(player);
                }
            }
        });
    }

    // =========================
    // SELF-LOGOUT via /ui auth logout (no password — chat passwords are forbidden)
    // Resets the stored session so the next join asks for the password again.
    // =========================
    public boolean logout(Player player) {
        UUID uuid = player.getUniqueId();
        if (!playerState.isAuthenticated(uuid)) return false;

        playerState.removeAuthenticated(uuid);
        playerState.removePendingAuth(uuid);
        AuthDatabase.resetAuth(uuid);

        // Plugin-style kick reason (same shape as the other auth kicks): the player must
        // see WHY the server closed the connection, not a bare disconnect.
        String kickLogout = MessagesManager.getString("auth.admin.kick_logout",
                "<yellow>✦</yellow> UltimateImprovements\n\n"
                        + "<red>❌ Session reset.</red>\n"
                        + "<gray>You logged out — enter your password again on the next login.</gray>");
        // Make sure the logout dialog is not left on screen behind the kick screen.
        AuthDialogScreen.close(player);
        player.kick(MessageUtil.parse(kickLogout));

        ConsoleLogger.info("[Auth] Player " + player.getName() + " logged out manually.");
        return true;
    }

    // =========================
    // FREEZE / UNFREEZE PLAYER
    // =========================
    private void freezePlayer(Player player) {
        UUID uuid = player.getUniqueId();

        // Recover any stale snapshot before taking a fresh one. This can happen
        // after a plugin hot-reload or an interrupted previous authentication.
        if (hasPersistedSavedState(player)) {
            recoverPersistedPlayerState(player);
        }

        SavedPlayerState leaked = savedStates.remove(uuid);
        if (leaked != null) {
            ConsoleLogger.warn("[Auth] Discarding leaked savedStates for " + player.getName()
                    + " (was " + leaked.gameMode + "/walk=" + leaked.walkSpeed + ")");
            try {
                restoreSavedState(player, leaked);
            } catch (Throwable ignored) {}
        }

        // Capture the real state once, before changing any movement or flight flag.
        SavedPlayerState saved = new SavedPlayerState(
                player.getGameMode(),
                player.getWalkSpeed(),
                player.getFlySpeed(),
                player.getAllowFlight(),
                player.isFlying(),
                player.isInvulnerable());
        savedStates.put(uuid, saved);

        player.setGameMode(GameMode.ADVENTURE);
        player.setWalkSpeed(0.0f);
        player.setFlySpeed(0.0f);
        player.setAllowFlight(false);
        player.setFlying(false);
        player.setInvulnerable(true);

        // Store a durable snapshot before the temporary permission is enabled.
        persistSavedState(player, saved);

        // allow-flight=false in server.properties otherwise treats the auth freeze
        // as unauthorized flight and kicks with "Flying is not enabled". The player
        // still cannot move/interact: AuthListener enforces pendingAuth, and fly speed
        // remains zero. This permission exists only while this snapshot is active.
        player.setAllowFlight(true);
        player.setFlySpeed(0.0f);

        // Hover instead of standing on gravity. The auth dialog uses pause=true,
        // which still leaves server-side physics running: a player frozen mid-air
        // kept falling every tick, the cancelled PlayerMoveEvent made the server
        // setback them, and each of those moves looked like a "missing dialog" to
        // AuthPrompt — it re-sent the screen and wiped the password being typed.
        // With flight enabled (fly speed is zero, movement stays cancelled) gravity
        // no longer moves the player, so no fall, no setback, no endless re-prompts.
        player.setFlying(true);
        player.setVelocity(new org.bukkit.util.Vector());
    }

    private void unfreezePlayer(Player player) {
        UUID uuid = player.getUniqueId();
        SavedPlayerState saved = savedStates.remove(uuid);
        if (saved == null) {
            saved = readPersistedSavedState(player);
        }

        if (saved != null) {
            restoreSavedState(player, saved);
        } else {
            restoreSafeDefaults(player);
        }

        clearPersistedSavedState(player);
        // The marker and restored permission are written together: a later restart
        // cannot resurrect a temporary auth-only flight grant.
        savePlayerData(player);
    }

    /**
     * Restores the player's saved state (used by forceLogin from AuthManager).
     */
    public void restorePlayerState(Player player) {
        unfreezePlayer(player);
    }

    /**
     * Per-player cleanup on PlayerQuitEvent. Restores the saved pre-freeze state (if any)
     * and persists it to player.dat so the next login doesn't start from the frozen state
     * (GM=ADVENTURE, walkSpeed=0, flySpeed=0, invulnerable=true).
     * <p>
     * Without this, a player who disconnects mid-auth would:
     *   1. Have a leaked savedStates entry, AND
     *   2. Have frozen state stored in player.dat
     * → on next join, freezePlayer captures the frozen state as "original"
     * → unfreeze restores frozen state → player stuck (can't move, can't fly).
     * <p>
     * playerState + timeout cleanup is handled separately by {@link AuthManager#removePlayer}.
     */
    public void handleQuit(Player player) {
        if (player == null) return;
        UUID uuid = player.getUniqueId();
        SavedPlayerState saved = savedStates.remove(uuid);
        if (saved == null) {
            saved = readPersistedSavedState(player);
        }

        if (saved != null || hasPersistedSavedState(player)) {
            try {
                if (saved != null) {
                    restoreSavedState(player, saved);
                } else {
                    restoreSafeDefaults(player);
                }
                clearPersistedSavedState(player);
                savePlayerData(player);
            } catch (Throwable t) {
                ConsoleLogger.warn("[Auth] handleQuit restore failed for " + player.getName()
                        + ": " + t.getMessage());
            }
        }
    }

    /**
     * Restores a player.dat snapshot left by an interrupted authentication before
     * the new join is evaluated. A valid auth session can then pass through normally;
     * an expired session gets a fresh snapshot and a fresh temporary flight grant.
     */
    public void recoverPersistedPlayerState(Player player) {
        if (player == null || !hasPersistedSavedState(player)) return;

        UUID uuid = player.getUniqueId();
        SavedPlayerState saved = readPersistedSavedState(player);
        if (saved == null) {
            saved = savedStates.remove(uuid);
        }

        try {
            if (saved != null) {
                restoreSavedState(player, saved);
            } else {
                ConsoleLogger.warn("[Auth] Invalid persisted auth snapshot for " + player.getName()
                        + "; applying safe movement defaults.");
                restoreSafeDefaults(player);
            }
            savedStates.remove(uuid);
            clearPersistedSavedState(player);
            savePlayerData(player);
            ConsoleLogger.info("[Auth] Recovered interrupted auth state for " + player.getName() + ".");
        } catch (Throwable t) {
            ConsoleLogger.warn("[Auth] Persisted auth recovery failed for " + player.getName()
                    + ": " + t.getMessage());
        }
    }

    /**
     * Called when the auth module is disabled or hot-reloaded. Online players are
     * unfrozen and every temporary flight marker is cleared before the manager dies.
     */
    public void shutdown() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            UUID uuid = player.getUniqueId();
            if (!playerState.isPendingAuth(uuid)
                    && !savedStates.containsKey(uuid)
                    && !hasPersistedSavedState(player)) {
                continue;
            }

            handleQuit(player);
            playerState.removePlayer(uuid);
            timeoutManager.cancelLoginTimeout(uuid);
            AuthPrompt.removePlayer(uuid);
            Auth2FA twofa = Auth2FA.getInstance();
            if (twofa != null) {
                twofa.clearPending(uuid);
            }
        }

        savedStates.clear();
        if (instance == this) {
            instance = null;
        }
    }

    // =========================
    // FAIL-CLOSED KICKS
    // =========================
    /**
     * Refuses the login when the auth database cannot be read. Letting the player
     * through ("skipping auth") would disable the password gate entirely.
     */
    private void kickStorageUnavailable(Player player) {
        timeoutManager.cancelLoginTimeout(player.getUniqueId());
        AuthDialogScreen.close(player);
        player.kick(MessageUtil.parse(MessagesManager.getString(
                "auth.admin.kick_storage_unavailable",
                "<yellow>✦</yellow> UltimateImprovements\n\n"
                        + "<red>❌ Authorization is temporarily unavailable.</red>\n"
                        + "<gray>The account database is unreachable. Try again later or contact an administrator.</gray>")));
    }

    /**
     * Refuses the login when 2FA is enabled but cannot be verified (DB error or a
     * missing GitHub binding). Never silently disables the second factor.
     */
    private void kickTwoFactorUnavailable(Player player, String reason) {
        timeoutManager.cancelLoginTimeout(player.getUniqueId());
        AuthDialogScreen.close(player);

        boolean missingBinding = "binding_missing".equals(reason);
        String key = missingBinding ? "auth.twofa.binding_missing" : "auth.twofa.storage_error";
        String def = missingBinding
                ? "<yellow>✦</yellow> UltimateImprovements\n\n"
                        + "<red>❌ 2FA is enabled but no GitHub account is linked.</red>\n"
                        + "<gray>Contact an administrator to restore the binding.</gray>"
                : "<yellow>✦</yellow> UltimateImprovements\n\n"
                        + "<red>❌ 2FA verification is temporarily unavailable.</red>\n"
                        + "<gray>The account database is unreachable. Try again later.</gray>";
        player.kick(MessageUtil.parse(MessagesManager.getString(key, def)));
    }

    // =========================
    // GET PLAYER IP
    // =========================
    private String getPlayerIp(Player player) {
        String ip = AuthClientAddress.getClientIp(player);
        if (ip == null) {
            ConsoleLogger.warn("[Auth] Could not resolve a valid client IP for "
                    + (player == null ? "<null>" : player.getName())
                    + " — refusing authorization.");
        }
        return ip;
    }

    /**
     * Ensures the proxy-aware client IP exists and is stored before a session is
     * granted. Public for the explicitly privileged force-login path in AuthManager.
     */
    public boolean ensureClientIpStored(Player player) {
        return storeClientIp(player, getPlayerIp(player));
    }

    private boolean storeClientIp(Player player, String ip) {
        if (ip == null || ip.isBlank()) {
            kickInvalidClientIp(player);
            return false;
        }
        if (!AuthDatabase.updateLastIp(player.getUniqueId(), ip)) {
            ConsoleLogger.warn("[Auth] Could not persist client IP for " + player.getName()
                    + " — refusing authorization.");
            kickStorageUnavailable(player);
            return false;
        }
        return true;
    }

    /**
     * Refuses authentication when Paper cannot provide a usable client IP.
     * This includes unresolved, wildcard and multicast addresses, plus loopback
     * addresses when auth.reject_loopback_ips is enabled. The optional loopback
     * guard can catch proxy/Unix-socket setups that failed to forward the real
     * client address without blocking direct localhost development by default.
     */
    private void kickInvalidClientIp(Player player) {
        UUID uuid = player.getUniqueId();
        timeoutManager.cancelLoginTimeout(uuid);
        playerState.removePlayer(uuid);
        AuthPrompt.removePlayer(uuid);
        Auth2FA twofa = Auth2FA.getInstance();
        if (twofa != null) twofa.clearPending(uuid);
        AuthDialogScreen.close(player);

        ConsoleLogger.warn("[Auth] Kicking " + player.getName()
                + ": no valid proxy-aware client IP was received.");
        player.kick(MessageUtil.parse(MessagesManager.getString(
                "auth.admin.kick_invalid_client_ip",
                "<yellow>✦</yellow> UltimateImprovements\n\n"
                        + "<red>❌ Authentication is unavailable: the server could not determine your client IP address.</red>\n"
                        + "<gray>The connection did not provide a valid client address. Check proxy forwarding configuration and try again.</gray>")));
    }
}
