/*
 * Copyright (C) 2024 Sonar Contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package xyz.jonesdev.sonar.common.fallback.verification;

import org.jetbrains.annotations.NotNull;
import xyz.jonesdev.sonar.api.Sonar;
import xyz.jonesdev.sonar.api.fallback.FallbackUser;
import xyz.jonesdev.sonar.common.fallback.protocol.FallbackPacket;
import xyz.jonesdev.sonar.common.fallback.protocol.FallbackPacketDecoder;
import xyz.jonesdev.sonar.common.fallback.protocol.FallbackPacketEncoder;
import xyz.jonesdev.sonar.common.fallback.protocol.FallbackPacketRegistry;
import xyz.jonesdev.sonar.common.fallback.protocol.packets.configuration.FinishConfigurationPacket;
import xyz.jonesdev.sonar.common.fallback.protocol.packets.login.LoginAcknowledgedPacket;
import xyz.jonesdev.sonar.common.fallback.protocol.packets.play.ClientInformationPacket;
import xyz.jonesdev.sonar.common.fallback.protocol.packets.play.KeepAlivePacket;
import xyz.jonesdev.sonar.common.fallback.protocol.packets.play.PluginMessagePacket;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

import static xyz.jonesdev.sonar.api.fallback.protocol.ProtocolVersion.*;
import static xyz.jonesdev.sonar.common.fallback.protocol.FallbackPreparer.*;
import static xyz.jonesdev.sonar.common.util.ProtocolUtil.BRAND_CHANNEL;
import static xyz.jonesdev.sonar.common.util.ProtocolUtil.BRAND_CHANNEL_LEGACY;

public final class FallbackPreJoinHandler extends FallbackVerificationHandler {

  public FallbackPreJoinHandler(final @NotNull FallbackUser user) {
    super(user);

    // Start initializing the actual join process for pre-1.20.2 clients
    if (user.getProtocolVersion().compareTo(MINECRAFT_1_20_2) < 0) {
      // This trick helps in reducing unnecessary outgoing server traffic
      // by avoiding sending other packets to clients that are potentially bots.
      if (user.getProtocolVersion().compareTo(MINECRAFT_1_8) < 0) {
        user.channel().eventLoop().schedule(this::markSuccess, 100L, TimeUnit.MILLISECONDS);
      } else {
        sendKeepAlive();
      }
    }
  }

  private boolean receivedClientInfo, receivedClientBrand, acknowledgedLogin;
  private int expectedKeepAliveId;

  /**
   * The purpose of these KeepAlive packets is to confirm that the connection
   * is active and legitimate, thereby preventing bot connections that
   * could flood the server with login attempts and other unwanted traffic.
   */
  private void sendKeepAlive() {
    // Send a KeepAlive packet with a random ID
    expectedKeepAliveId = RANDOM.nextInt();
    user.write(new KeepAlivePacket(expectedKeepAliveId));
  }

  private void markAcknowledged() {
    acknowledgedLogin = true;
    // Update state, so we're able to send/receive configuration packets
    updateEncoderDecoderState(FallbackPacketRegistry.CONFIG);
    synchronizeClientRegistry();
    // Write the FinishConfiguration packet to the buffer
    user.delayedWrite(FINISH_CONFIGURATION);
    // Send all packets in one flush
    user.channel().flush();
  }

  private void markSuccess() {
    // Pass the player to the next verification handler
    final var decoder = user.channel().pipeline().get(FallbackPacketDecoder.class);
    decoder.setListener(new FallbackGravityHandler(user, this));
  }

  void validateClientInformation() {
    checkState(receivedClientInfo, "didn't send client settings");
    // Don't check Geyser players for plugin messages as they don't have them
    if (!user.isGeyser()) {
      checkState(receivedClientBrand, "didn't send plugin message");
    }
  }

  @Override
  public void handle(final @NotNull FallbackPacket packet) {
    if (packet instanceof KeepAlivePacket) {
      // This is the first packet we expect from the client
      final KeepAlivePacket keepAlive = (KeepAlivePacket) packet;

      // Check if the KeepAlive ID matches the expected ID
      final long keepAliveId = keepAlive.getId();
      checkState(keepAliveId == expectedKeepAliveId,
        "expected K ID " + expectedKeepAliveId + " but got " + keepAliveId);

      // 1.8 clients send KeepAlive packets with the ID 0 every second
      // while the player is in the "Downloading terrain" screen.
      expectedKeepAliveId = 0;

      // Immediately verify the player if they do not need any configuration (pre-1.20.2)
      if (user.getProtocolVersion().compareTo(MINECRAFT_1_20_2) < 0) {
        markSuccess();
      }
    } else if (packet instanceof LoginAcknowledgedPacket) {
      // Prevent users from sending multiple LoginAcknowledged packets
      checkState(!acknowledgedLogin, "sent duplicate login ack");
      markAcknowledged();
    } else if (packet instanceof FinishConfigurationPacket) {
      // Update the encoder and decoder state because we're currently in the CONFIG state
      updateEncoderDecoderState(FallbackPacketRegistry.GAME);
      validateClientInformation();
      markSuccess();
    } else if (packet instanceof ClientInformationPacket) {
      final ClientInformationPacket clientInformation = (ClientInformationPacket) packet;

      checkState(clientInformation.getViewDistance() >= 2,
        "view distance: " + clientInformation.getViewDistance());
      // Ensure that the client locale is correct
      validateClientLocale(clientInformation.getLocale());
      // Check if the player sent an unused bit flag in the skin section
      // TODO: check if this causes issues with cosmetics in pvp clients
      checkState((clientInformation.getSkinParts() & 0x80) == 0,
        "sent unused bit flag: " + clientInformation.getSkinParts());

      receivedClientInfo = true;
    } else if (packet instanceof PluginMessagePacket) {
      final PluginMessagePacket pluginMessage = (PluginMessagePacket) packet;

      final boolean usingModernChannel = pluginMessage.getChannel().equals(BRAND_CHANNEL);
      final boolean usingLegacyChannel = pluginMessage.getChannel().equals(BRAND_CHANNEL_LEGACY);

      // Skip this payload if it does not contain client brand information
      if (!usingModernChannel && !usingLegacyChannel) {
        return;
      }

      // Make sure the player isn't sending the client brand multiple times
      checkState(!receivedClientBrand, "sent duplicate plugin message");
      // Check if the channel is correct - 1.13 uses the new namespace
      // system ('minecraft:' + channel) and anything below 1.13 uses
      // the legacy namespace system ('MC|' + channel).
      checkState(usingLegacyChannel || user.getProtocolVersion().compareTo(MINECRAFT_1_13) >= 0,
        "illegal PluginMessage channel: " + pluginMessage.getChannel());

      // Validate the client branding using a regex to filter unwanted characters.
      if (Sonar.get().getConfig().getVerification().getBrand().isEnabled()) {
        validateClientBrand(pluginMessage.getData());
      }

      receivedClientBrand = true;
    }
  }

  private void updateEncoderDecoderState(final @NotNull FallbackPacketRegistry registry) {
    // Update the packet registry state in the encoder and decoder pipelines
    user.channel().pipeline().get(FallbackPacketDecoder.class).updateRegistry(registry);
    user.channel().pipeline().get(FallbackPacketEncoder.class).updateRegistry(registry);
  }

  private void synchronizeClientRegistry() {
    // 1.20.5+ adds new "game bundle features" which overcomplicate all of this...
    if (user.getProtocolVersion().compareTo(MINECRAFT_1_20_5) >= 0) {
      // Write the new RegistrySync packets to the buffer
      for (final FallbackPacket syncPacket : user.getProtocolVersion().compareTo(MINECRAFT_1_21) < 0
        ? REGISTRY_SYNC_1_20_5 : REGISTRY_SYNC_1_21) {
        user.delayedWrite(syncPacket);
      }
    } else {
      // Write the old RegistrySync packet to the buffer
      user.delayedWrite(REGISTRY_SYNC_LEGACY);
    }
  }

  private void validateClientBrand(final byte @NotNull [] data) {
    // Check if the client brand is too short. It has to have at least 2 bytes.
    checkState(data.length > 1, "client brand is too short");
    // Check if the decoded client brand string is too long
    checkState(data.length < Sonar.get().getConfig().getVerification().getBrand().getMaxLength(),
      "client brand contains too much data: " + data.length);
    // https://discord.com/channels/923308209769426994/1116066363887321199/1256929441053933608
    String brand = new String(data, StandardCharsets.UTF_8);
    // Remove the invalid character at the beginning of the client brand
    if (user.getProtocolVersion().compareTo(MINECRAFT_1_8) >= 0) {
      brand = brand.substring(1);
    }
    // Check for illegal client brands
    checkState(!brand.equals("Vanilla"), "illegal client brand: " + brand);
    // Regex pattern for validating client brands
    final Pattern pattern = Sonar.get().getConfig().getVerification().getBrand().getValidRegex();
    checkState(pattern.matcher(brand).matches(), "client brand does not match pattern: " + brand);
  }

  private void validateClientLocale(final @NotNull String locale) {
    // Check the client locale by performing a simple regex check
    // that disallows non-ascii characters by default.
    final Pattern pattern = Sonar.get().getConfig().getVerification().getValidLocaleRegex();
    checkState(pattern.matcher(locale).matches(), "client locale does not match pattern: " + locale);
  }
}
