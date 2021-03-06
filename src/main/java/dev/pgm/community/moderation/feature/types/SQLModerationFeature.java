package dev.pgm.community.moderation.feature.types;

import com.google.common.collect.Lists;
import dev.pgm.community.Community;
import dev.pgm.community.database.DatabaseConnection;
import dev.pgm.community.moderation.ModerationConfig;
import dev.pgm.community.moderation.feature.ModerationFeatureBase;
import dev.pgm.community.moderation.punishments.Punishment;
import dev.pgm.community.moderation.punishments.PunishmentType;
import dev.pgm.community.moderation.punishments.types.MutePunishment;
import dev.pgm.community.moderation.services.SQLModerationService;
import dev.pgm.community.users.feature.UsersFeature;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.logging.Logger;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.configuration.Configuration;
import org.bukkit.entity.Player;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent.Result;

public class SQLModerationFeature extends ModerationFeatureBase {

  private SQLModerationService service;

  public SQLModerationFeature(
      Configuration config, Logger logger, DatabaseConnection connection, UsersFeature usernames) {
    super(new ModerationConfig(config), logger, usernames);
    this.service = new SQLModerationService(connection, getModerationConfig());
    logger.info("Punishments (SQL) have been enabled");
  }

  @Override
  public void save(Punishment punishment) {
    if (getModerationConfig().isPersistent()) {

      // When issuing a new ban or mute, check for existing and pardon if any.
      switch (punishment.getType()) {
        case TEMP_BAN:
        case BAN:
          isBanned(punishment.getTargetId().toString())
              .thenAcceptAsync(
                  banned -> {
                    if (banned) {
                      service
                          .pardon(punishment.getTargetId(), punishment.getIssuerId())
                          .thenAcceptAsync(x -> service.save(punishment));
                    } else {
                      service.save(punishment);
                    }
                  });
          break;
        case MUTE:
          isMuted(punishment.getTargetId())
              .thenAcceptAsync(
                  mute -> {
                    if (mute.isPresent()) {
                      service
                          .unmute(punishment.getTargetId(), punishment.getIssuerId())
                          .thenAcceptAsync(x -> service.save(punishment));
                    } else {
                      service.save(punishment);
                    }
                  });
          break;
        default:
          service.save(punishment);
          break;
      }
    }
  }

  @Override
  public CompletableFuture<List<Punishment>> query(String target) {
    if (UsersFeature.USERNAME_REGEX.matcher(target).matches()) {
      // CONVERT TO UUID if username
      return getUsers()
          .getStoredId(target)
          .thenApplyAsync(
              uuid ->
                  uuid != null && uuid.isPresent()
                      ? service.queryList(uuid.get().toString()).join()
                      : Lists.newArrayList());
    }
    return service.queryList(target);
  }

  @Override
  public CompletableFuture<Boolean> pardon(String target, Optional<UUID> issuer) {
    if (UsersFeature.USERNAME_REGEX.matcher(target).matches()) {
      return getUsers()
          .getStoredId(target)
          .thenApplyAsync(
              uuid -> {
                if (uuid.isPresent()) {
                  removeCachedBan(uuid.get());
                  return service.pardon(uuid.get(), issuer).join();
                }
                return false;
              });
    }
    UUID id = UUID.fromString(target);
    removeCachedBan(id);
    return service.pardon(id, issuer);
  }

  @Override
  public CompletableFuture<Boolean> isBanned(String target) {
    if (UsersFeature.USERNAME_REGEX.matcher(target).matches()) {
      return getUsers()
          .getStoredId(target)
          .thenApplyAsync(
              uuid -> uuid.isPresent() ? service.isBanned(uuid.get().toString()).join() : false);
    }
    return service.isBanned(target);
  }

  @Override
  public void onPreLogin(AsyncPlayerPreLoginEvent event) {
    List<Punishment> punishments;
    try {
      punishments =
          service
              .queryList(event.getUniqueId().toString())
              .get(getModerationConfig().getLoginTimeout(), TimeUnit.SECONDS);

      Optional<Punishment> ban = hasActiveBan(punishments);
      if (ban.isPresent()) {
        Punishment punishment = ban.get();

        event.setKickMessage(
            punishment.formatPunishmentScreen(
                getModerationConfig(), getUsers().renderUsername(punishment.getIssuerId()).join()));
        event.setLoginResult(Result.KICK_BANNED);
      }

      Optional<MutePunishment> mute = hasActiveMute(punishments);
      if (mute.isPresent()) {
        addMute(event.getUniqueId(), mute.get());
      }

      logger.info(
          punishments.size()
              + " Punishments have been fetched for "
              + event.getUniqueId().toString());
    } catch (InterruptedException | ExecutionException e) {
      event.setLoginResult(Result.KICK_OTHER);
      event.setKickMessage(
          ChatColor.DARK_RED + "Error joining, please try again."); // TODO: Pretty this up
      e.printStackTrace();
    } catch (TimeoutException e) {
      scheduleDelayedCheck(event.getUniqueId());
    }
  }

  private void scheduleDelayedCheck(UUID playerId) {
    Community.get()
        .getServer()
        .getScheduler()
        .scheduleSyncDelayedTask(
            Community.get(),
            new Runnable() {
              @Override
              public void run() {
                Player player = Bukkit.getPlayer(playerId);
                if (player != null) {
                  service
                      .queryList(playerId.toString())
                      .thenAcceptAsync(
                          punishments -> {
                            Optional<Punishment> ban = hasActiveBan(punishments);
                            if (ban.isPresent()) {
                              Punishment punishment = ban.get();

                              player.kickPlayer(
                                  punishment.formatPunishmentScreen(
                                      getModerationConfig(),
                                      getUsers().renderUsername(punishment.getIssuerId()).join()));
                            }

                            Optional<MutePunishment> mute = hasActiveMute(punishments);
                            if (mute.isPresent()) {
                              addMute(playerId, mute.get());
                            }

                            logger.info(
                                "[Delayed]: "
                                    + punishments.size()
                                    + " Punishments have been fetched for "
                                    + playerId.toString());
                          });
                }
              }
            });
  }

  private Optional<MutePunishment> hasActiveMute(List<Punishment> punishments) {
    return punishments.stream()
        .filter(
            p ->
                p.isActive()
                    && p.getType().equals(PunishmentType.MUTE)
                    && p.getService().equalsIgnoreCase(getModerationConfig().getService()))
        .map(MutePunishment.class::cast)
        .findAny();
  }

  private Optional<Punishment> hasActiveBan(List<Punishment> punishments) {
    return punishments.stream()
        .filter(
            p ->
                p.isActive()
                    && PunishmentType.isBan(p.getType())
                    && p.getService().equalsIgnoreCase(getModerationConfig().getService()))
        .findAny();
  }

  @Override
  public CompletableFuture<Optional<Punishment>> isMuted(UUID target) {
    return service.isMuted(target);
  }

  @Override
  public CompletableFuture<Boolean> unmute(UUID id, Optional<UUID> issuer) {
    removeMute(id);
    return service.unmute(id, issuer);
  }

  @Override
  public CompletableFuture<List<Punishment>> getRecentPunishments(Duration period) {
    return service.getRecentPunishments(period);
  }

  @Override
  public CompletableFuture<Integer> count() {
    return service.count();
  }
}
