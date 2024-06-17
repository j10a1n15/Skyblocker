package de.hysky.skyblocker.utils;

import java.nio.ByteBuffer;
import java.security.PrivateKey;
import java.security.Signature;
import java.util.Base64;
import java.util.Objects;
import java.util.UUID;

import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

import com.google.gson.JsonParser;
import com.mojang.logging.LogUtils;
import com.mojang.serialization.Codec;
import com.mojang.serialization.JsonOps;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import de.hysky.skyblocker.SkyblockerMod;
import de.hysky.skyblocker.utils.scheduler.Scheduler;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.minecraft.SharedConstants;
import net.minecraft.client.MinecraftClient;
import net.minecraft.network.encryption.PlayerKeyPair;
import net.minecraft.text.Text;
import net.minecraft.util.Uuids;
import net.minecraft.util.dynamic.Codecs;

public class ApiAuthentication {
	private static final Logger LOGGER = LogUtils.getLogger();
	private static final MinecraftClient CLIENT = MinecraftClient.getInstance();
	private static final String MINECRAFT_VERSION = SharedConstants.getGameVersion().getName();
	private static final String AUTH_URL = "https://hysky.de/api/aaron/authenticate";
	private static final String CONTENT_TYPE = "application/json";
	private static final String ALGORITHM = "SHA256withRSA";

	private static TokenInfo tokenInfo = null;

	public static void init() {
		//Update token after the profileKeys instance is initialized
		ClientLifecycleEvents.CLIENT_STARTED.register(_client -> updateToken());
	}

	private static void updateToken() {
		//The fetching runs async in ProfileKeysImpl#getKeyPair
		CLIENT.getProfileKeys().fetchKeyPair().thenAcceptAsync(playerKeypairOpt -> {
			if (playerKeypairOpt.isPresent()) {
				PlayerKeyPair playerKeyPair = playerKeypairOpt.get();

				//The key header and footer can be sent but that doesn't matter to the server
				String publicKey = Base64.getMimeEncoder().encodeToString(playerKeyPair.publicKey().data().key().getEncoded());
				byte[] publicKeySignature = playerKeyPair.publicKey().data().keySignature();
				long expiresAt = playerKeyPair.publicKey().data().expiresAt().toEpochMilli();

				TokenRequest.KeyPairInfo keyPairInfo = new TokenRequest.KeyPairInfo(Objects.requireNonNull(CLIENT.getSession().getUuidOrNull()), publicKey, publicKeySignature, expiresAt);
				TokenRequest.SignedData signedData = Objects.requireNonNull(getRandomSignedData(playerKeyPair.privateKey()));
				TokenRequest tokenRequest = new TokenRequest(keyPairInfo, signedData, SkyblockerMod.SKYBLOCKER_MOD.getMetadata().getId(), MINECRAFT_VERSION, SkyblockerMod.VERSION);

				String request = SkyblockerMod.GSON.toJson(TokenRequest.CODEC.encodeStart(JsonOps.INSTANCE, tokenRequest).getOrThrow());

				try {
					tokenInfo = TokenInfo.CODEC.parse(JsonOps.INSTANCE, JsonParser.parseString(Http.sendPostRequest(AUTH_URL, request, CONTENT_TYPE))).getOrThrow();
					int refreshAtTicks = (int) (((tokenInfo.expiresAt() - tokenInfo.issuedAt()) / 1000L) - 300L) * 20; //Refresh 5 minutes before expiry date

					Scheduler.INSTANCE.schedule(ApiAuthentication::updateToken, refreshAtTicks, true);
				} catch (Exception e) {
					//Try again in 1 minute
					logErrorAndScheduleRetry(Text.translatable("skyblocker.api.token.authFailure"), 60 * 20, "[Skyblocker Api Auth] Failed to refresh the api token! Some features might not work :(", e);
				}
			} else {
				//The Minecraft Services API is probably down so we will retry in 5 minutes, either that or your access token has expired (game open for 24h) and you need to restart.
				logErrorAndScheduleRetry(Text.translatable("skyblocker.api.token.noProfileKeys"), 300 * 20, "[Skyblocker Api Auth] Failed to fetch profile keys! Some features may not work temporarily :( (Has your game been open for more than 24 hours? If so restart.)");
			}
		}).exceptionally(throwable -> {
			//Try again in 1 minute
			logErrorAndScheduleRetry(Text.translatable("skyblocker.api.token.authFailure"), 60 * 20, "[Skyblocker Api Auth] Encountered an unexpected exception while refreshing the api token!", throwable);

			return null;
		});
	}

	private static TokenRequest.SignedData getRandomSignedData(PrivateKey privateKey) {
		try {
			Signature signature = Signature.getInstance(ALGORITHM);
			UUID uuid = UUID.randomUUID();
			ByteBuffer buf = ByteBuffer.allocate(16)
					.putLong(uuid.getMostSignificantBits())
					.putLong(uuid.getLeastSignificantBits());

			signature.initSign(privateKey);
			signature.update(buf.array());

			byte[] signedData = signature.sign();

			return new TokenRequest.SignedData(buf.array(), signedData);
		} catch (Exception e) {
			LOGGER.error("[Skyblocker Api Auth] Failed to sign random data!", e);
		}

		//This should never ever be the case, since we are signing data that is not invalid in any case
		return null;
	}

	private static void logErrorAndScheduleRetry(Text warningMessage, int retryAfter, String logMessage, Object... logArgs) {
		LOGGER.error(logMessage, logArgs);
		Scheduler.INSTANCE.schedule(ApiAuthentication::updateToken, retryAfter, true);

		if (CLIENT.player != null) CLIENT.player.sendMessage(Constants.PREFIX.get().append(warningMessage));
	}

	@Nullable
	public static String getToken() {
		return tokenInfo != null ? tokenInfo.token() : null;
	}

	private record TokenRequest(KeyPairInfo keyPairInfo, SignedData signedData, String mod, String minecraftVersion, String modVersion) {
		private static final Codec<TokenRequest> CODEC = RecordCodecBuilder.create(instance -> instance.group(
				KeyPairInfo.CODEC.fieldOf("keyPair").forGetter(TokenRequest::keyPairInfo),
				SignedData.CODEC.fieldOf("signedData").forGetter(TokenRequest::signedData),
				Codec.STRING.fieldOf("mod").forGetter(TokenRequest::mod),
				Codec.STRING.fieldOf("minecraftVersion").forGetter(TokenRequest::minecraftVersion),
				Codec.STRING.fieldOf("modVersion").forGetter(TokenRequest::modVersion))
				.apply(instance, TokenRequest::new));

		private record KeyPairInfo(UUID uuid, String publicKey, byte[] publicKeySignature, long expiresAt) {
			private static final Codec<KeyPairInfo> CODEC = RecordCodecBuilder.create(instance -> instance.group(
					Uuids.STRING_CODEC.fieldOf("uuid").forGetter(KeyPairInfo::uuid),
					Codec.STRING.fieldOf("publicKey").forGetter(KeyPairInfo::publicKey),
					Codecs.BASE_64.fieldOf("publicKeySignature").forGetter(KeyPairInfo::publicKeySignature),
					Codec.LONG.fieldOf("expiresAt").forGetter(KeyPairInfo::expiresAt))
					.apply(instance, KeyPairInfo::new));
		}

		private record SignedData(byte[] original, byte[] signed) {
			private static final Codec<SignedData> CODEC = RecordCodecBuilder.create(instance -> instance.group(
					Codecs.BASE_64.fieldOf("original").forGetter(SignedData::original),
					Codecs.BASE_64.fieldOf("signed").forGetter(SignedData::signed))
					.apply(instance, SignedData::new));
		}
	}

	private record TokenInfo(String token, long issuedAt, long expiresAt) {
		private static final Codec<TokenInfo> CODEC = RecordCodecBuilder.create(instance -> instance.group(
				Codec.STRING.fieldOf("token").forGetter(TokenInfo::token),
				Codec.LONG.fieldOf("issuedAt").forGetter(TokenInfo::issuedAt),
				Codec.LONG.fieldOf("expiresAt").forGetter(TokenInfo::expiresAt))
				.apply(instance, TokenInfo::new));
	}
}
