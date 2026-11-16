package com.mockcoin.gateway.admin.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import tools.jackson.databind.ObjectMapper;
import com.mockcoin.gateway.admin.config.GatewayAdminProperties;
import com.mockcoin.gateway.admin.domain.AdminCredentialRecord;
import com.mockcoin.gateway.admin.integration.GatewayAdminCredentialRepository;
import com.mockcoin.gateway.admin.integration.AdminWebAuthnChallengeStore;
import com.mockcoin.gateway.admin.integration.AdminWebAuthnChallengeStore.Purpose;
import com.yubico.webauthn.AssertionRequest;
import com.yubico.webauthn.AssertionResult;
import com.yubico.webauthn.FinishAssertionOptions;
import com.yubico.webauthn.FinishRegistrationOptions;
import com.yubico.webauthn.RegistrationResult;
import com.yubico.webauthn.RelyingParty;
import com.yubico.webauthn.StartAssertionOptions;
import com.yubico.webauthn.StartRegistrationOptions;
import com.yubico.webauthn.data.AuthenticatorAssertionResponse;
import com.yubico.webauthn.data.AuthenticatorAttestationResponse;
import com.yubico.webauthn.data.AuthenticatorSelectionCriteria;
import com.yubico.webauthn.data.ByteArray;
import com.yubico.webauthn.data.ClientAssertionExtensionOutputs;
import com.yubico.webauthn.data.ClientRegistrationExtensionOutputs;
import com.yubico.webauthn.data.PublicKeyCredential;
import com.yubico.webauthn.data.PublicKeyCredentialCreationOptions;
import com.yubico.webauthn.data.RelyingPartyIdentity;
import com.yubico.webauthn.data.ResidentKeyRequirement;
import com.yubico.webauthn.data.UserIdentity;
import com.yubico.webauthn.data.UserVerificationRequirement;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;

// Yubico webauthn-server-core를 이용한 admin 패스키 등록/로그인 challenge 처리 서비스
@Service
public class GatewayAdminWebAuthnService {
    private static final Logger log = LoggerFactory.getLogger(GatewayAdminWebAuthnService.class);
    private static final Duration CHALLENGE_TTL = Duration.ofMinutes(5);

    private final GatewayAdminProperties properties;
    private final GatewayAdminCredentialRepository credentialRepository;
    private final AdminWebAuthnChallengeStore challengeStore;
    private final ObjectMapper objectMapper;
    private RelyingParty relyingParty;

    // 관리자 설정, 자격증명 저장소, challenge 저장소, ObjectMapper를 주입받는다(RelyingParty는 init에서 만든다).
    public GatewayAdminWebAuthnService(
            GatewayAdminProperties properties,
            GatewayAdminCredentialRepository credentialRepository,
            AdminWebAuthnChallengeStore challengeStore,
            ObjectMapper objectMapper) {
        this.properties = properties;
        this.credentialRepository = credentialRepository;
        this.challengeStore = challengeStore;
        this.objectMapper = objectMapper;
    }

    // 설정의 RP ID·이름과 자격증명 저장소로 RelyingParty를 만들고, origin 목록이 있으면 허용 origin으로 지정한다.
    @PostConstruct
    void init() {
        RelyingPartyIdentity rpIdentity = RelyingPartyIdentity.builder()
                .id(properties.webauthnRpId())
                .name(properties.webauthnRpName())
                .build();
        RelyingParty.RelyingPartyBuilder builder = RelyingParty.builder()
                .identity(rpIdentity)
                .credentialRepository(credentialRepository);
        if (!properties.webauthnOrigins().isEmpty()) {
            builder.origins(properties.webauthnOrigins());
        }
        relyingParty = builder.build();
    }

    // 패스키가 등록되어 있는지 여부를 위임 반환한다
    public boolean hasRegisteredPasskey() {
        return credentialRepository.hasRegisteredPasskey();
    }

    // WebAuthn 등록용 challenge/PublicKey credential 옵션을 생성한다
    public Map<String, Object> generateRegistrationOptions() {
        try {
            ByteArray userHandle = computeUserHandle(properties.username());
            UserIdentity userIdentity = UserIdentity.builder()
                    .name(properties.username())
                    .displayName(properties.username())
                    .id(userHandle)
                    .build();
            PublicKeyCredentialCreationOptions options = relyingParty.startRegistration(StartRegistrationOptions.builder()
                    .user(userIdentity)
                    .authenticatorSelection(AuthenticatorSelectionCriteria.builder()
                            .residentKey(ResidentKeyRequirement.PREFERRED)
                            .userVerification(UserVerificationRequirement.PREFERRED)
                            .build())
                    .build());
            String challengeId = UUID.randomUUID().toString();
            challengeStore.save(
                    Purpose.REGISTRATION,
                    challengeId,
                    options.toJson(),
                    Instant.now().plus(CHALLENGE_TTL));
            challengeStore.purgeExpired(Instant.now());
            return Map.of("challengeId", challengeId, "options", objectMapper.readTree(options.toCredentialsCreateJson()));
        } catch (Exception exception) {
            log.error("Failed to generate gateway admin WebAuthn registration options", exception);
            throw new IllegalStateException("WebAuthn registration options generation failed", exception);
        }
    }

    // 등록 응답을 검증하고 자격증명을 저장한다 (allowReplace=true면 기존 자격증명 교체 허용)
    public boolean finishRegistration(String responseJson, String challengeId, boolean allowReplace) {
        Optional<String> requestJson = challengeStore.take(Purpose.REGISTRATION, challengeId);
        if (requestJson.isEmpty()) {
            return false;
        }
        try {
            PublicKeyCredentialCreationOptions options = PublicKeyCredentialCreationOptions.fromJson(requestJson.get());
            PublicKeyCredential<AuthenticatorAttestationResponse, ClientRegistrationExtensionOutputs> credential = PublicKeyCredential.parseRegistrationResponseJson(responseJson);
            RegistrationResult result = relyingParty.finishRegistration(FinishRegistrationOptions.builder()
                    .request(options)
                    .response(credential)
                    .build());
            AdminCredentialRecord record = new AdminCredentialRecord(
                    properties.username(),
                    result.getKeyId().getId(),
                    computeUserHandle(properties.username()),
                    result.getPublicKeyCose(),
                    0L);
            if (allowReplace) {
                credentialRepository.save(record);
                return true;
            }
            return credentialRepository.saveIfAbsent(record);
        } catch (Exception exception) {
            log.error("Failed to finish gateway admin WebAuthn registration", exception);
            return false;
        }
    }

    // WebAuthn 로그인용 challenge/assertion 옵션을 생성한다
    public Map<String, Object> generateLoginOptions() {
        try {
            AssertionRequest request = relyingParty.startAssertion(StartAssertionOptions.builder()
                    .username(properties.username())
                    .userVerification(UserVerificationRequirement.PREFERRED)
                    .build());
            String challengeId = UUID.randomUUID().toString();
            challengeStore.save(
                    Purpose.LOGIN,
                    challengeId,
                    request.toJson(),
                    Instant.now().plus(CHALLENGE_TTL));
            challengeStore.purgeExpired(Instant.now());
            return Map.of("challengeId", challengeId, "options", objectMapper.readTree(request.toCredentialsGetJson()));
        } catch (Exception exception) {
            log.error("Failed to generate gateway admin WebAuthn login options", exception);
            throw new IllegalStateException("WebAuthn login options generation failed", exception);
        }
    }

    // 패스키 로그인 응답을 검증하고 성공 시 sign count를 갱신한다
    public boolean finishLogin(String responseJson, String challengeId) {
        Optional<String> requestJson = challengeStore.take(Purpose.LOGIN, challengeId);
        if (requestJson.isEmpty()) {
            return false;
        }
        try {
            AssertionRequest request = AssertionRequest.fromJson(requestJson.get());
            PublicKeyCredential<AuthenticatorAssertionResponse, ClientAssertionExtensionOutputs> credential = PublicKeyCredential.parseAssertionResponseJson(responseJson);
            AssertionResult result = relyingParty.finishAssertion(FinishAssertionOptions.builder()
                    .request(request)
                    .response(credential)
                    .build());
            if (!result.isSuccess()) {
                return false;
            }
            credentialRepository.updateSignCount(result.getCredential().getCredentialId(), result.getSignatureCount());
            return true;
        } catch (Exception exception) {
            log.error("Failed to finish gateway admin WebAuthn login", exception);
            return false;
        }
    }

    // 등록·로그인 옵션 생성이 멈춰도 버려진 challenge가 쌓이지 않도록 주기적으로(기본 60초) 정리한다.
    @Scheduled(fixedDelayString = "${mockcoin.gateway.admin.in-memory-sweep-millis:60000}")
    void scheduledPurge() {
        challengeStore.purgeExpired(Instant.now());
    }

    // username의 SHA-256 해시로 고정된 userHandle을 만든다.
    private static ByteArray computeUserHandle(String username) {
        try {
            return new ByteArray(MessageDigest.getInstance("SHA-256").digest(username.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 not available", exception);
        }
    }
}
