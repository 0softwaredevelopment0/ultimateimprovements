# Permissions

UltimateImprovements registers permission nodes in code. The full inventory below is extracted from the current \`ui-src/ui-core/.../Permissions.java\` source file. It is a **name inventory**, not a declaration that every node is granted by default or that every command uses only one node.

## Broad permission nodes

| Node | Meaning |
|---|---|
| \`ui.*\` | Broad UI permission wildcard |
| \`ui.command.*\` | Command namespace wildcard |
| \`ui.alerts\` | Receive server alerts |
| \`ui.show.brand\` | Show the server brand |
| \`ui.sudo\` | Sudo-related permission |
| \`ui.anticheat.bypass\` | Anti-cheat bypass |
| \`ui.anticheat.notify\` | Anti-cheat notifications |
| \`ui.chat.filter.bypass\` | Chat-filter bypass |
| \`ui.packetguard.bypass\` | PacketGuard bypass |
| \`ui.gmprotect.bypass\` | Game-mode protection bypass |
| \`ui.creative.bypass\` | Creative item validation bypass |
| \`ui.sculk.hazards.bypass\` | Sculk hazard bypass (explicit grant rule applies; see below) |

A wildcard is not a universal bypass for every subsystem. Some security features intentionally require an explicit exact permission node rather than relying on \`*\`, \`ui.*\` or OP status.

## Practical examples

- For command access, grant the exact node used by the command, such as \`ui.command.radview\`.
- For a staff group, use a purpose-built set of nodes rather than automatically granting \`ui.*\`.
- Keep destructive and security-sensitive powers separate: \`sudo\`, OP management, console lockdown, power management, offline inventory editing and punishment actions should not be bundled into an ordinary moderator role.
- Confirm the effective node in LuckPerms and verify it in-game with a non-OP test account.

## Special cases documented by the implementation

### Radiation overlay

\`/ui radview on|off\` uses \`ui.command.radview\`. It is an administrative overlay and is separate from the Dosimeter item.

### Config reset and regeneration

\`/ui config regen\` and \`/ui config reset\` require the corresponding permission (\`ui.command.configregen\` or \`ui.command.configreset\`) and are additionally gated by the \`config.commands_enabled\` setting in UI-Core.toml. The action also uses a confirmation flow. The extra gate is disabled by default in the bundled core template.

### Item Stealing enchantment

The Item Stealing enchantment checks \`ui.enchant.itemstealing.steal\` before the steal effect can trigger. Its default is documented as false in the project guide.

### Sculk hazard bypass

\`ui.sculk.hazards.bypass\` is checked as an **explicit exact grant**. Wildcards such as \`*\` or \`ui.*\` and plain OP status do not count as an explicit grant for this bypass. This is intentional: staff can still be exposed to the hazard unless the exact node is granted.

### Nether portal controls

The guide documents separate permissions for toggle, mode and status operations. In the current guide, status is available to everyone; changing the toggle or target mode is administrative. Confirm the exact default in the release's implementation.

### Permissions with granular sub-actions

Some command families have child permissions. Examples from source include punishments, kits, auth administration, votes, OP commands, restart controls, report moderation, and code-pane key management. Granting a parent command node does not necessarily grant every administrative sub-action.

## Complete permission constant inventory

The current core source contains 204 permission-string declarations representing 203 distinct node values. The following list contains those unique nodes. Treat it as a spelling reference; code paths and default permission registration determine actual access.

- `ui`
- `ui.*`
- `ui.alerts`
- `ui.anticheat.bypass`
- `ui.anticheat.notify`
- `ui.breaker.bypasstier`
- `ui.breaker.use`
- `ui.breaker.use.`
- `ui.chat.channel.admin`
- `ui.chat.channel.check`
- `ui.chat.channel.global`
- `ui.chat.channel.local`
- `ui.chat.channel.private`
- `ui.chat.channel.world`
- `ui.chat.custom.bypass`
- `ui.chat.filter.bypass`
- `ui.command.*`
- `ui.command.ac`
- `ui.command.addons`
- `ui.command.askpos`
- `ui.command.auth`
- `ui.command.auth.chgpass`
- `ui.command.auth.delsession`
- `ui.command.auth.forcelogin`
- `ui.command.auth.resetauth`
- `ui.command.autorestart.reset`
- `ui.command.autorestart.skip`
- `ui.command.autorestart.status`
- `ui.command.autorestart.toggle`
- `ui.command.autorestart.undoskip`
- `ui.command.blacklist`
- `ui.command.bossbar`
- `ui.command.broadcast`
- `ui.command.chatchnl`
- `ui.command.check`
- `ui.command.checkver`
- `ui.command.chgdim`
- `ui.command.chgdim.*`
- `ui.command.clan`
- `ui.command.clan.home.bypasscooldown`
- `ui.command.clan.remove`
- `ui.command.clear.attributes`
- `ui.command.clear.chat`
- `ui.command.cmdlog`
- `ui.command.codepane`
- `ui.command.codepane.key`
- `ui.command.codepane.key.add`
- `ui.command.codepane.key.list`
- `ui.command.codepane.key.modify`
- `ui.command.codepane.key.remove`
- `ui.command.configregen`
- `ui.command.configreset`
- `ui.command.console`
- `ui.command.craftrecipe`
- `ui.command.crash`
- `ui.command.delhome`
- `ui.command.deop`
- `ui.command.dura`
- `ui.command.enchant`
- `ui.command.endersee`
- `ui.command.execchat`
- `ui.command.expsplit`
- `ui.command.feed`
- `ui.command.fly`
- `ui.command.fly.other`
- `ui.command.flyspeed`
- `ui.command.forceop`
- `ui.command.forcesuicide`
- `ui.command.getpos`
- `ui.command.god`
- `ui.command.god.other`
- `ui.command.heal`
- `ui.command.help`
- `ui.command.home`
- `ui.command.inv.edit`
- `ui.command.invsee`
- `ui.command.itemnbt`
- `ui.command.kit`
- `ui.command.kit.cooldown`
- `ui.command.kit.cooldown.bypass`
- `ui.command.kit.create`
- `ui.command.kit.delete`
- `ui.command.kit.export`
- `ui.command.kit.give`
- `ui.command.kit.give.force`
- `ui.command.kit.give.other`
- `ui.command.kit.globalcd`
- `ui.command.kit.globalcd.bypass`
- `ui.command.kit.import`
- `ui.command.kit.rename`
- `ui.command.kit.resetcd`
- `ui.command.kit.resetglobalcd`
- `ui.command.language`
- `ui.command.listhomes`
- `ui.command.maintenance`
- `ui.command.menu`
- `ui.command.meteor.spawn`
- `ui.command.modreport`
- `ui.command.money`
- `ui.command.near`
- `ui.command.netherportal.mode`
- `ui.command.netherportal.status`
- `ui.command.netherportal.toggle`
- `ui.command.notes`
- `ui.command.op`
- `ui.command.opblacklist`
- `ui.command.opdelhome`
- `ui.command.ophomels`
- `ui.command.oplist`
- `ui.command.opself`
- `ui.command.opself.bypasscooldown`
- `ui.command.opwhitelist`
- `ui.command.pdc`
- `ui.command.pingsound`
- `ui.command.plugin`
- `ui.command.plugins`
- `ui.command.power.off`
- `ui.command.power.reboot`
- `ui.command.power.schedule`
- `ui.command.power.undo`
- `ui.command.protection`
- `ui.command.punish`
- `ui.command.punish.ban`
- `ui.command.punish.block`
- `ui.command.punish.crash`
- `ui.command.punish.kick`
- `ui.command.punish.listwarns.other`
- `ui.command.punish.listwarns.self`
- `ui.command.punish.logs`
- `ui.command.punish.logs.purge`
- `ui.command.punish.mute`
- `ui.command.punish.pardon`
- `ui.command.punish.pardon.confirm`
- `ui.command.punish.pardon.mass`
- `ui.command.punish.pardon.unban`
- `ui.command.punish.pardon.unblock`
- `ui.command.punish.pardon.unmute`
- `ui.command.punish.pardon.unwarn`
- `ui.command.punish.warn`
- `ui.command.radview`
- `ui.command.redstone.list`
- `ui.command.reload`
- `ui.command.report`
- `ui.command.reports`
- `ui.command.repstatus`
- `ui.command.rtp`
- `ui.command.rtp.bypasscooldown`
- `ui.command.rtp.other`
- `ui.command.scoreboard`
- `ui.command.scoreboardnick`
- `ui.command.sculk.start`
- `ui.command.sculk.status`
- `ui.command.sculk.stop`
- `ui.command.server`
- `ui.command.sethome`
- `ui.command.setrad`
- `ui.command.setspawn`
- `ui.command.sharepos`
- `ui.command.showcraft`
- `ui.command.showspeed`
- `ui.command.space`
- `ui.command.spawn`
- `ui.command.stresstest`
- `ui.command.sudo`
- `ui.command.suicide`
- `ui.command.swapjar`
- `ui.command.turret`
- `ui.command.unlock`
- `ui.command.updatejar`
- `ui.command.uuid`
- `ui.command.vanish`
- `ui.command.version`
- `ui.command.vote`
- `ui.command.vote.bypass`
- `ui.command.vote.change`
- `ui.command.vote.change.other`
- `ui.command.vote.create`
- `ui.command.vote.delete.other`
- `ui.command.vote.stats`
- `ui.command.whitelist`
- `ui.command.wirelessbind`
- `ui.creative.bypass`
- `ui.enchant.aoe.bypass`
- `ui.fireball.use`
- `ui.gmprotect.bypass`
- `ui.gsit.spin`
- `ui.overload.logs`
- `ui.packetguard.bypass`
- `ui.proxy.server`
- `ui.punish.notify`
- `ui.scoreboardnick.bypass.filter`
- `ui.scoreboardnick.bypass.length`
- `ui.scoreboardnick.bypass.length.max`
- `ui.scoreboardnick.bypass.length.min`
- `ui.scoreboardnick.bypass.regex`
- `ui.scoreboardnick.bypass.wildcard`
- `ui.scoreboardnick.others`
- `ui.scoreboardnick.toggle`
- `ui.sculk.hazards.bypass`
- `ui.serverlockdown.bypass`
- `ui.show.brand`
- `ui.sudo`

## How to assign permissions

Example LuckPerms commands (adapt group names and nodes to your server policy):

\`\`\`text
/lp group moderator permission set ui.command.help true
/lp group moderator permission set ui.command.report true
/lp group moderator permission set ui.command.reports true
/lp group moderator permission set ui.command.check true
/lp group moderator permission set ui.command.uncheck true
\`\`\`

Do not copy the examples blindly: verify that the selected nodes match the exact command and the intended moderation scope. A permission node in this list is not evidence that every command is safe to grant to every staff rank.
