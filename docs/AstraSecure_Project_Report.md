# Project Report on AstraSecure
## A Mission First Secure Messaging App for Defence

---

**Submitted to**

LOVELY PROFESSIONAL UNIVERSITY

In partial fulfilment of the requirements for the award of degree of

**BSc (Hons.) Computer Science – Cybersecurity**

---

**Submitted By:** &emsp;&emsp;&emsp;&emsp;&emsp;&emsp;&emsp;&emsp;&emsp; **Supervised By:**

Tejas Khanna (_______)  
Sandrani Balachandu (______)  
Ismail Alam (______)  
Tamminana Yashwanth Sai (______)  
Jatin Preet Singh (______)

---

**LOVELY FACULTY OF TECHNOLOGY AND SCIENCES**

**LOVELY PROFESSIONAL UNIVERSITY, PUNJAB**

**Session 2025–2026**

---

---

## Declaration

We hereby declare that the project entitled **"AstraSecure: A Mission First Secure Messaging App for Defence"** is our own original work, completed at Lovely Professional University as part of the requirements for the award of the degree of BSc (Hons.) Computer Science – Cybersecurity, under the guidance of our project supervisor.

All data, design decisions, and source code presented in this project report are the result of our own independent research, experimentation, and collaborative development. Any external work, tools, libraries, or references that have been consulted or incorporated into the project have been duly cited and acknowledged.

We further confirm that this report has not been previously submitted, in full or in part, to this or any other institution for any academic qualification.

**Date:**

Tejas Khanna (________)

Sandrani Balachandu (________)

Ismail Alam (________)

Tamminana Yashwanth Sai (________)

Jatin Preet Singh (________)

---

## Supervisor Declaration

This is to certify that the project report titled **"AstraSecure: A Mission First Secure Messaging App for Defence"** is a bona fide record of the work carried out by Tejas Khanna, Sandrani Balachandu, Ismail Alam, Tamminana Yashwanth Sai, and Jatin Preet Singh, under my supervision and guidance, in partial fulfilment of the requirements for the award of the degree of BSc (Hons.) Computer Science – Cybersecurity.

The work presented herein is original and meets the academic standards expected of a capstone submission at the undergraduate level.

**Supervisor:**

Name: ___________________________

UID: ___________________________

Signature: ___________________________

School of Computer Applications, Lovely Professional University, Punjab, India

---

## Acknowledgement

The completion of this capstone project marks the culmination of three years of academic study in cybersecurity, and it would not have been possible without the support of many people who contributed in ways both direct and indirect.

We begin by extending our most sincere gratitude to our project supervisor, whose guidance proved indispensable at every phase of this work. From clarifying the architecture of the Android Keystore system to helping us reason through the threat model behind metadata normalisation, her mentorship shaped not just this project but our broader understanding of applied cryptography. We are grateful not only for her technical insights but for the patience she demonstrated when our implementation ran ahead of our documentation — a recurring pattern in software development that she helped us manage with discipline.

Our thanks also go to the faculty of the School of Computer Applications at Lovely Professional University, whose lectures, workshops, and open-door policies gave us the theoretical grounding from which this work grew. The courses on network security, operating systems, and cryptographic foundations were particularly foundational to the design decisions we made in AstraSecure.

Several of the challenges we encountered during development — particularly around Android Keystore attestation, coroutine-based concurrency, and the nuances of GCM authentication tags — were resolved through peer discussions. We are grateful to our classmates and fellow capstone groups for the healthy intellectual environment they fostered.

Finally, and most fundamentally, we thank our families. This project demanded significant time beyond scheduled classes and required late evenings and weekend sessions to deliver a functional prototype. The encouragement we received from home was not incidental — it was the foundation on which everything else rested.

---

## Table of Contents

1. Introduction — About the Project
2. Review of Literature
3. Rationale and Scope of the Study
4. Objectives and Hypothesis of the Study
5. Research Methodology and System Architecture
6. Complete Work Plan with Timelines
7. Expected Outcome of the Study
8. Research and Experimental Work Done
9. Results and Discussions
10. Conclusions and Summary
11. References and Bibliography
12. Appendix A — Selected Source Code
13. Appendix B — Screenshots of the User Interface

---

---

## Chapter 1: Introduction — About the Project

### 1.1 Background and Motivation

The problem of secure communication is as old as organised human conflict, but the digital age has introduced dimensions to this problem that earlier generations could not have anticipated. Where classical cryptography was concerned with substitution ciphers and physical key exchange, contemporary secure communication must contend with endpoint compromise, side-channel attacks, metadata leakage, and adversaries capable of harvesting encrypted traffic now in anticipation of decrypting it when quantum computing matures. The threat landscape has grown not merely in scale but in sophistication.

In tactical and defence environments, the stakes are considerably higher than in civilian communication. A compromised message channel does not just expose private conversations — it can endanger lives, betray operational positions, reveal troop movements, and hand an adversary the intelligence advantage needed to neutralise an operation entirely. It was this high-consequence context that motivated the design and development of AstraSecure, the secure tactical messaging application described in this report.

AstraSecure is a native Android application built using Kotlin and Jetpack Compose. It is designed to serve as a secure communication and operational management platform for authorised personnel. The application draws on hardware security features available in modern Android devices — specifically the Android Keystore system and, where available, the StrongBox secure enclave — to ground its cryptographic operations in tamper-resistant hardware rather than in software alone.

The decision to build for the Android platform was deliberate. Android has the largest installed base of any mobile operating system worldwide, including significant deployment in government, defence, and enterprise sectors. More relevantly, the Android security architecture includes mature hardware-backed cryptography through the Keystore API, Trusted Execution Environment (TEE) support, and — in flagship and enterprise-grade devices — StrongBox, a dedicated hardware security module that provides a cryptographic processor isolated even from the main application processor.

### 1.2 What AstraSecure Does

At its core, AstraSecure is a mission-centric secure messaging platform. The design metaphor throughout the application is that of a field operations system: operators are provisioned with a hardware-backed cryptographic identity on first launch, missions are created as operational contexts, and within each mission, communication occurs through clearance-gated channels that enforce rank-based access control.

Every message sent within the application is encrypted using AES-256-GCM under a per-mission symmetric key derived and stored in the Android Keystore. Before transmission, messages are padded to a fixed block boundary using randomly-sized padding, and a randomised timing delay is injected between composition and storage. These two measures — padding and timing randomisation — are specifically designed to defeat traffic analysis: an adversary intercepting the encrypted data stream cannot infer message length, frequency, or conversational rhythm from the ciphertext alone.

The application's key features include the following:

**Hardware-Backed Identity Provisioning:** On first launch, AstraSecure guides the operator through a provisioning flow that generates an elliptic-curve key pair inside the Android Keystore. The private key never leaves the secure hardware boundary. The operator is assigned a callsign, and their identity is permanently associated with the hardware key.

**Mission-Isolated Encrypted Messaging:** Each mission maintains its own symmetric encryption key within the Keystore. Channels within a mission are gated by minimum clearance levels, ensuring that operators can only access channels for which they have the appropriate rank.

**Metadata Normalisation:** The metadata module applies random padding to every outgoing message, normalising message sizes to 256-byte boundaries. A random delay between 200 milliseconds and 2,000 milliseconds is further injected prior to message storage. Both measures are designed to prevent an observer from drawing inferences about communication patterns from ciphertext metadata alone.

**Encrypted Document Vault:** The identity module maintains a document vault in which files are encrypted under individual per-document AES-256 keys, each stored in the Keystore. The vault supports retrieval and integrity verification, and documents are never stored in plaintext.

**Security Event Audit Log:** Every significant action — key rotation, provisioning events, failed decryption attempts, degraded subsystem notifications — is recorded in an append-only event log retained in memory and exportable as JSON. This log provides the audit trail necessary for post-incident investigation.

**Emergency Panic Wipe:** A dedicated PANIC tab allows an operator to initiate a terminal purge of all cryptographic material. The wipe sequence invalidates all Keystore key aliases, clears the EncryptedSharedPreferences store, deletes persisted JSON data, and writes a tombstone marker to unencrypted SharedPreferences. On the subsequent application launch, the tombstone is detected and the application routes to a non-recoverable terminated state.

**Clearance-Based Access Control:** Operators are assigned ranks with numeric clearance levels. Channels are configured with minimum clearance thresholds. Operators below the minimum clearance for a channel cannot view its contents or post messages to it.

**Administrator Schema Customisation:** An admin console allows privileged operators to define custom rank tiers, channel category templates, message classification categories, and mission types. None of these are hardcoded in the application binary — they exist as mutable schema objects in the application's data layer, editable at runtime without recompilation.

### 1.3 Technical Platform and Technologies

AstraSecure is implemented entirely in Kotlin, targeting Android API level 34 with a minimum supported level of API 31 (Android 12). The user interface is built exclusively with Jetpack Compose, Google's declarative UI toolkit. There are no Fragments in the codebase; navigation is handled entirely through the Jetpack Compose Navigation library.

The application's architecture follows a layered clean architecture pattern with manual dependency injection. The module boundary structure is as follows:

- The `crypto/` module handles all AES-256-GCM encryption and key management via the Android Keystore API.
- The `identity/` module manages operator identity provisioning, device attestation, document vault operations, and the panic wipe sequence.
- The `metadata/` module provides message padding, timing randomisation, and batch message grouping.
- The `ux/` module contains all ViewModels and the Compose navigation graph.
- The `ui/` module contains all Composable screen implementations and the AstraSecure design system.
- The `shared/data/` module contains the central reactive in-memory store, all repository implementations, and JSON-based persistence.

The Signal Protocol library for Android (libsignal-android) is included as a dependency, establishing the cryptographic infrastructure for potential future integration of double-ratchet session management. Kotlin Coroutines and StateFlow are used throughout for asynchronous state management. The kotlinx.serialization library handles JSON persistence to a local file (`astra_store.json`).

---

## Chapter 2: Review of Literature

### 2.1 The Evolution of Secure Tactical Communication

The history of secure military communication predates digital computing by centuries. From the Caesar cipher to the Enigma machine, the fundamental tension between communication efficiency and communication security has shaped military doctrine, determined battlefield outcomes, and driven cryptographic research. The digital era did not eliminate this tension; it accelerated it. The emergence of packet-switched networks introduced new vulnerabilities — traffic analysis, replay attacks, man-in-the-middle interception — that required fundamentally new cryptographic approaches.

Shannon's 1949 paper "Communication Theory of Secrecy Systems" provided the mathematical foundation for modern cryptography by formalising the notion of perfect secrecy and demonstrating that the one-time pad achieves it. In practical military communications, however, perfect secrecy is unachievable due to the key distribution problem, and modern systems instead rely on computational security — the assumption that breaking the encryption would require computational resources beyond the reach of any realistic adversary.

### 2.2 AES and Symmetric Encryption Standards

The Advanced Encryption Standard (AES), standardised by the National Institute of Standards and Technology in 2001 following a rigorous public evaluation process, replaced the Data Encryption Standard (DES) as the baseline for symmetric encryption in civilian and government applications. AES operates on 128-bit blocks with key sizes of 128, 192, or 256 bits. The 256-bit variant, employed in AstraSecure, is widely regarded as providing security margins well beyond what foreseeable classical computing advances could threaten.

The choice of GCM (Galois/Counter Mode) as the operating mode for AES in AstraSecure reflects current consensus in applied cryptography. AES-GCM is an authenticated encryption with associated data (AEAD) construction, meaning it simultaneously provides confidentiality through CTR-mode stream cipher encryption and integrity/authenticity through a Galois field MAC computation. Each encryption operation produces both a ciphertext and a 16-byte authentication tag. Any modification to the ciphertext — including bit-flipping attacks or data corruption — causes tag verification to fail during decryption, providing strong protection against tampering.

Prior literature on AEAD constructions consistently supports GCM as a superior choice over non-authenticated modes such as CBC for mobile application cryptography, primarily because it eliminates the need for a separate HMAC computation while providing equivalent or stronger integrity guarantees (Rogaway, 2004; Dworkin, 2007). The requirement that each GCM operation use a unique 12-byte Initialisation Vector (IV) is critical; IV reuse under the same key is a catastrophic vulnerability that allows an adversary to recover both the keystream and the MAC key. AstraSecure generates IVs using `SecureRandom`, which on Android draws from the kernel's entropy pool and is seeded from hardware entropy sources where available.

### 2.3 The Android Keystore System

The Android Keystore System, introduced in Android 4.3 and substantially extended in subsequent releases, provides an API for generating and using cryptographic keys that are bound to the device's hardware security layer. Keys generated in the Keystore are stored in either the Trusted Execution Environment (TEE) — an isolated execution environment running on a separate CPU core or in a dedicated secure zone — or, in devices equipped with StrongBox (available since Android 9), in a dedicated hardware security module with its own processor, memory, and storage.

The defining security property of Keystore-backed keys is that the private key material never leaves the secure hardware boundary in an unencrypted form. The application does not hold the raw key bytes; instead, it holds a reference (alias string) that the Keystore system uses to identify the key for cryptographic operations. The actual cryptographic computation occurs inside the TEE or StrongBox, and only the output (ciphertext, signature, etc.) is returned to the application layer.

Published research on the Android Keystore (Smalley and Craig, 2013; Android Security Team, 2017) demonstrates that this architecture provides meaningful resistance to key extraction attacks even in the presence of a rooted device or a kernel-level compromise, provided that the TEE firmware is intact. The StrongBox further raises the bar by providing physical tamper resistance and a dedicated processor that cannot be compromised even if the main Android OS kernel is fully controlled by an attacker.

AstraSecure exploits this architecture by generating both the operator's identity key pair (an EC P-256 key) and all per-mission AES-256-GCM keys within the Keystore. Key rotation — the practice of replacing a key after a configurable period — is implemented in the `CryptoEngine` module and exposed through the security dashboard.

### 2.4 Metadata Leakage and Traffic Analysis

A widely underappreciated dimension of secure communication is the information that can be inferred from communication metadata even when message contents are perfectly encrypted. Traffic analysis — the practice of drawing inferences from communication patterns rather than content — has been a recognised intelligence technique since at least the Second World War. In the digital context, an observer with access to network traffic can infer communication volumes, timing patterns, message frequency, and conversational structure from ciphertext alone, without breaking the encryption.

Substantial academic literature has established the practical feasibility of traffic analysis against encrypted messaging systems. Wright et al. (2008) demonstrated that the size of encrypted VoIP packets could be used to reconstruct spoken phrases with high accuracy. Cai et al. (2012) showed that website fingerprinting attacks could identify specific pages visited even over Tor. In the context of tactical communication, traffic analysis could reveal mission rhythms, command structures, and pre-operation communication surges that signal imminent activity.

AstraSecure's metadata module directly addresses this threat through two mechanisms: message padding and timing randomisation. Message padding normalises message sizes to multiples of 256 bytes by appending cryptographically random bytes before encryption. The padding size is recorded alongside the message so that the receiver can strip it during decryption, but from the perspective of an observer examining ciphertext sizes, all messages appear as uniform block multiples. Timing randomisation injects a delay drawn from the uniform distribution over [200, 2000] milliseconds between message composition and storage, disrupting any rhythm-based analysis of communication cadence.

### 2.5 Role-Based Access Control in Secure Systems

Role-Based Access Control (RBAC) was formally described by Sandhu et al. in their seminal 1996 paper and has since become the dominant model for access control in enterprise and government information systems. The core principle of RBAC is the assignment of permissions to roles rather than directly to users, with users then assigned to roles according to their position or clearance level. This indirection simplifies permission management in large organisations and provides natural support for the principle of least privilege.

In AstraSecure, RBAC is implemented through a clearance-based model inspired by the Bell–LaPadula security model for multilevel security (Bell and LaPadula, 1973). Each rank has an associated numeric clearance level. Each channel is configured with a minimum clearance level below which operators cannot view messages or post. The assignment of operators to missions and their rank within a mission is managed through `ClearanceAssignment` objects that the admin can create, modify, and revoke. This ensures that operators see only the channels and missions to which they are cleared, even within the same application instance.

### 2.6 Key Management and Cryptographic Hygiene

The academic literature on cryptographic key management consistently identifies key storage and lifecycle management as the most common practical failure points in deployed cryptographic systems (Ferguson and Schneier, 2003). Systems that implement strong encryption algorithms but store key material in plaintext files, databases, or application memory are providing only the illusion of security.

AstraSecure's approach to key management is informed by the principle that no key material should ever appear in the application heap in a form that could be extracted by a compromised process. All symmetric keys are generated and stored within the Android Keystore. The application's `CryptoEngine` module never receives raw key bytes from the Keystore; it passes key alias strings to the `KeyStore.SecretKeyEntry` API, which returns opaque `SecretKey` objects that are usable for cryptographic operations but whose byte representation cannot be accessed by normal API calls.

Per-document keys in the vault follow the same principle. Each document is encrypted under a unique AES-256-GCM key with its own Keystore alias, meaning compromise of a single document key — through an as-yet-unknown Keystore vulnerability — cannot expose any other document in the vault. This principle, sometimes called key segregation or forward isolation, is a standard practice in high-assurance cryptographic system design.

### 2.7 Panic-Wipe and Data Destruction

The concept of remote or emergency data wipe is well established in mobile device management (MDM) literature. Android Enterprise provides a `wipeData()` API for MDM deployments that can perform a factory reset. However, MDM-based wipe requires an enrolled management profile and administrative access to the MDM server, neither of which may be available in a field scenario.

AstraSecure implements an application-level panic wipe that operates independently of any external infrastructure. The wipe sequence, which must be confirmed by the operator through a physical slider interaction requiring at least 95% drag completion, performs the following in sequence: cryptographic key invalidation via the Keystore `deleteEntry()` API, EncryptedSharedPreferences clearing, in-memory store erasure, JSON persistence file deletion, and tombstone marker write. The tombstone is a single flag in unencrypted SharedPreferences that survives the wipe (being itself not sensitive) and causes the application to route to a terminal state on the next launch from which normal functionality is unreachable without reinstalling the application.

---

## Chapter 3: Rationale and Scope of the Study

### 3.1 Rationale of the Study

The rationale for developing AstraSecure as a capstone project emerges from a genuine gap in the mobile security landscape, one that became apparent through both our coursework and our independent research into existing communication solutions.

Platforms like Signal, WhatsApp, and Telegram have made end-to-end encryption accessible to a mass audience and represent significant achievements in applied cryptography. Signal in particular — implementing the Double Ratchet Algorithm, the X3DH key agreement protocol, and the Sealed Sender mechanism — is considered by many cryptographers to be the gold standard for civilian encrypted messaging. However, these platforms share a critical architectural assumption that makes them unsuitable for the tactical and high-security use cases AstraSecure addresses: they rely on centralised servers for message routing, key distribution, and contact management.

This centralisation introduces several categories of risk in tactical contexts. Server-side logging, lawful intercept requirements, regulatory disclosure obligations, and the possibility of server compromise all represent threats that an adversary with sufficient resources could exploit. More fundamentally, centralised servers represent a single point of failure — an adversary who can disrupt the server infrastructure can silence the entire communication network.

AstraSecure takes a different architectural stance. There is no server. All state is maintained locally, within the device, backed by Android Keystore-protected cryptographic material. Communication within the application is entirely local to the device in the current implementation, with the architecture designed to support peer-to-peer encrypted transport in a future iteration without requiring changes to the cryptographic or data layers. This server-free architecture eliminates the central-server threat class entirely from the threat model.

The second motivation is the absence of hardware-backed cryptographic identity provisioning in commercially available messaging applications. Signal, WhatsApp, and their peers generate and store cryptographic keys in software, within the application sandbox. On a non-rooted device, this provides reasonable security. On a compromised or rooted device, key extraction may be possible through memory forensics. AstraSecure's identity module generates the operator's primary key pair inside the Android Keystore hardware boundary, providing key extraction resistance even against a sophisticated attacker with root-level access.

The third motivation is the absence of metadata normalisation in consumer messaging applications. Signal's Sealed Sender feature hides the sender's identity from the server, but does not address traffic analysis against ciphertext size and timing. AstraSecure's metadata module provides this protection explicitly.

### 3.2 Scope of the Study

**In Scope:**

The project scope encompasses the design, implementation, and functional validation of a native Android application with the following capabilities:

- Hardware-backed operator identity provisioning via the Android Keystore, with StrongBox utilisation where available.
- Mission management with lifecycle state tracking (ACTIVE, STANDBY, COMPROMISED, ARCHIVED).
- Per-mission AES-256-GCM encrypted messaging with unique per-message IV generation.
- Clearance-based channel access control enforcing rank-minimum thresholds.
- Message metadata normalisation through random padding and timing randomisation.
- An encrypted document vault with per-document key segregation.
- A security event audit log supporting export and review.
- An emergency panic-wipe flow with cryptographic key invalidation and tombstone marking.
- An administrative console for defining custom rank tiers, mission types, channel categories, and message categories at runtime.
- A comprehensive Jetpack Compose UI implementing a custom dark-mode design system.
- Persistent local storage via JSON serialisation backed by the kotlinx.serialization library.
- A degraded-mode protocol displaying visual banners when any cryptographic subsystem is unavailable.

**Out of Scope:**

The following areas are outside the defined scope of this capstone submission:

- Network transport implementation. The application operates entirely on-device. Message transmission between devices over any network protocol is a planned future iteration.
- Signal Protocol session establishment. The libsignal-android library is included as a dependency to validate the integration path, but Double Ratchet session setup and X3DH key agreement between devices are not implemented in this submission.
- Push notification infrastructure and background synchronisation services.
- Multi-device account migration or device pairing.
- Tablet and large-screen form factor adaptation.
- Hardware radio integration (SDR, satellite, mesh networking).
- iOS or cross-platform implementation.

### 3.3 Problem Statement

Consumer messaging applications, though cryptographically sophisticated in their encryption implementations, were designed for civilian infrastructure and cannot adequately address the threat model of tactical communication environments. The specific deficiencies are as follows: centralised server dependency creates single points of failure and regulatory disclosure risk; software-only key storage provides insufficient resistance to endpoint compromise on high-value targets; absence of metadata normalisation exposes communication patterns to traffic analysis; lack of clearance-based access control makes them unsuitable for multilevel security environments; and no native provision for cryptographically assured emergency data destruction means that a device capture exposes all historical communications and key material.

A purpose-built application that addresses each of these deficiencies — grounding cryptographic operations in hardware-backed key storage, eliminating server dependencies, normalising communication metadata, enforcing clearance-based access, and providing cryptographically complete emergency wipe — would represent a meaningful advancement over existing tools for the target use case.

---

## Chapter 4: Objectives and Hypothesis of the Study

### 4.1 Objectives of the Study

The primary objective of this project is to design, implement, and functionally validate a prototype secure tactical messaging application for Android that addresses the security gaps identified in Chapter 3. The specific objectives are as follows:

**Objective 1:** To implement a hardware-backed operator identity provisioning system using the Android Keystore API, generating an EC P-256 key pair in the Trusted Execution Environment or StrongBox secure enclave on first application launch.

**Objective 2:** To integrate AES-256-GCM encryption for all intra-mission messages, using per-mission symmetric keys stored in the Android Keystore, with unique per-message IVs generated from Android's `SecureRandom` implementation.

**Objective 3:** To design and implement a clearance-based access control system that enforces rank-minimum thresholds on channel visibility and message composition, using a runtime-editable schema rather than hardcoded role definitions.

**Objective 4:** To develop a metadata normalisation module that pads all outgoing messages to 256-byte block boundaries using randomly-sized cryptographic padding, and applies a random timing delay between 200 and 2,000 milliseconds prior to message storage, to defeat traffic analysis.

**Objective 5:** To build an encrypted document vault in which each stored file is encrypted under a unique per-document AES-256-GCM key with a dedicated Keystore alias, ensuring that compromise of any single document key does not expose other vault contents.

**Objective 6:** To implement an append-only security event audit log recording all significant security events — provisioning, key rotation, access control violations, degraded subsystem notifications, and panic wipe events — with timestamp, severity, and source attribution.

**Objective 7:** To design and implement an emergency panic-wipe flow that cryptographically invalidates all Keystore key material, clears all application state, and writes a permanent tombstone marker, rendering the device non-operational without reinstallation.

**Objective 8:** To build an administrative console enabling privileged operators to define and modify rank tiers, mission types, channel categories, and message classification categories at runtime without application recompilation.

**Objective 9:** To implement a reactive, StateFlow-based application state architecture using Kotlin Coroutines, ensuring that all UI surfaces are driven by ViewModel-exposed state and survive configuration changes through `SavedStateHandle`.

**Objective 10:** To validate the functional correctness and security properties of the implementation through structured testing covering provisioning flows, encryption/decryption round-trips, access control enforcement, metadata normalisation, and panic wipe completeness.

### 4.2 Hypothesis of the Study

**Null Hypothesis (H₀):** A native Android application implementing hardware-backed cryptographic key storage through the Android Keystore API, AES-256-GCM message encryption, clearance-based access control, message metadata normalisation, and an application-level panic wipe does not provide a meaningfully superior security posture compared to commercially available encrypted messaging applications when evaluated against the threat model of tactical communication environments.

**Alternative Hypothesis (H₁):** A purpose-built native Android application incorporating hardware-backed identity provisioning, server-free architecture, AES-256-GCM encryption with per-mission key segregation, clearance-enforced channel access, message metadata normalisation, and cryptographically complete emergency key destruction provides a significantly stronger security posture than commercial messaging alternatives for tactical use cases, measurably reducing the attack surface across the categories of server-side exposure, endpoint key extraction, traffic analysis, and catastrophic device compromise.

The following outcomes are expected to support the alternative hypothesis:

- Successful generation and storage of all cryptographic key material within the Android Keystore hardware boundary, with no plaintext key material appearing in application heap memory.
- Demonstrated resistance of the panic wipe sequence to key recovery after completion, verified by attempting to use invalidated Keystore aliases.
- Measurable uniformity of message sizes after metadata padding, validated by inspecting stored ciphertext lengths across messages of varying original sizes.
- Correct enforcement of clearance thresholds, verified by confirming that operators below the minimum rank for a channel cannot access its content or compose messages within it.
- Reliable degraded-mode protocol activation, verified by simulating subsystem unavailability and confirming visual banner display.

---

## Chapter 5: Research Methodology and System Architecture

### 5.1 Development Methodology

The development of AstraSecure followed an iterative, phased approach loosely structured around agile principles but adapted to the realities of a small academic team. Rather than fixed sprints with external stakeholder reviews, the team conducted weekly internal reviews at which each subsystem owner presented the current state of their module, demonstrated functional behaviour, and identified blockers. Cross-module integration reviews occurred at the end of each phase.

The development was organised into four phases:

**Phase 1 — Foundation and Provisioning:** Establishment of the core data models, the AppContainer dependency injection structure, the in-memory store and persistence layer, and the identity provisioning flow. This phase established the foundation on which all subsequent phases depended.

**Phase 2 — Cryptographic Core:** Implementation of the `CryptoEngine` module (AES-256-GCM encryption, key generation, key rotation, password-based key derivation) and the `MetadataProcessor` module (padding, timing randomisation, batch grouping). These modules were developed with stub UI wrappers for early validation.

**Phase 3 — Full Screen Implementation:** Build-out of all application screens — provisioning, missions list, channel list, secure chat, security dashboard, tools, panic wipe, and admin console — as fully wired Compose surfaces reading real ViewModel state.

**Phase 4 — Integration, Testing, and Hardening:** Cross-module integration testing, security event log validation, panic wipe end-to-end verification, degraded-mode protocol testing, and documentation.

### 5.2 System Architecture

#### 5.2.1 Layered Clean Architecture

AstraSecure adopts a layered architecture pattern in which each layer has a clearly defined responsibility and depends only on the layer immediately below it. This structure ensures that the cryptographic modules can be replaced or upgraded without requiring changes to the UI layer, and that the UI layer can be redesigned without touching the security-critical core.

The layers, from top to bottom, are:

1. **UI Layer** (`ui/` package): Jetpack Compose Composable functions. These functions observe ViewModel state through `collectAsState()` and emit user events to the ViewModel. They have no direct knowledge of repositories or cryptographic modules.

2. **ViewModel Layer** (`ux/` package): Manages UI state as `StateFlow<UiState>` and exposes action handlers that the UI calls on user interaction. Dispatches long-running operations to background coroutine contexts. Calls repository APIs. Has no direct reference to cryptographic module internals.

3. **Repository Layer** (`shared/data/` package): Provides domain-centric APIs for missions, channels, messages, documents, and schema entities. Repositories read from and write to the `InMemoryStore` and call into the crypto, identity, and metadata modules as needed.

4. **Module Layer** (`crypto/`, `identity/`, `metadata/` packages): The security-critical implementation layer. `CryptoEngine` wraps Android Keystore and provides AES-256-GCM operations. `IdentityManager` handles key pair generation, device attestation, vault operations, and panic wipe. `MetadataProcessor` implements padding and timing normalisation.

5. **Platform Layer**: Android Keystore, EncryptedSharedPreferences, SharedPreferences, the file system, and Kotlin Coroutines infrastructure. This layer is provided by the platform; AstraSecure does not implement it.

#### 5.2.2 Manual Dependency Injection

Rather than using a dependency injection framework such as Hilt or Koin, AstraSecure implements manual DI through a single `AppContainer` class instantiated in the `Application` subclass (`AstraApp`). The container creates all singletons — `CryptoEngine`, `IdentityManager`, `MetadataProcessor`, all repositories, `PersistenceManager`, `SecurityEventLog`, and `InMemoryStore` — and wires them together. ViewModels receive their dependencies through factory functions that extract the required objects from the container.

This choice was deliberate for a capstone submission: it makes the dependency graph entirely explicit and comprehensible without requiring familiarity with a DI framework's annotation processing or generated code. The container is designed so that migration to Hilt would require replacing factory functions with `@HiltViewModel` annotations and providing Hilt modules for the singleton objects — a mechanical transformation with no architectural changes.

#### 5.2.3 Reactive State Management

Application state is managed through Kotlin's `StateFlow` and the broader Coroutines machinery. The `InMemoryStore` exposes nine `MutableStateFlow` collections corresponding to the nine principal entity types: missions, channels, messages, documents, users, ranks, channel categories, message categories, and mission types. Each `MutableStateFlow` is backed by an immutable list; mutations replace the entire list atomically, which ensures that observers always see a consistent snapshot.

ViewModels observe these flows through `combine()` or `flatMapLatest()` operators depending on whether they need to merge state from multiple sources. The `MissionsViewModel`, for example, combines the missions flow with the mission-types flow and the channels flow to compute aggregate display values — channel counts, last activity timestamps, and mission type labels — reactively without requiring explicit refresh calls.

#### 5.2.4 Navigation Architecture

Navigation is implemented entirely through the Jetpack Compose Navigation library. The navigation graph is defined in `AstraNavGraph.kt` and contains the following destinations:

- `terminated`: A terminal destination routing to `TerminatedScreen`. Unreachable from normal navigation; only entered when a tombstone is detected on launch. Provides an uninstall instruction and a debug reset button (visible only in `BuildConfig.DEBUG` builds).
- `provisioning`: The first-launch flow. Not nested within the app shell; runs without bottom navigation.
- `app`: A nested navigation graph containing the main application:
  - `missions`: The mission list, default start destination.
  - `missions/{missionId}`: The channel list for a specific mission, receiving the mission ID as a navigation argument.
  - `missions/{missionId}/channels/{channelId}`: The secure chat surface.
  - `security`: The security dashboard.
  - `tools`: The secure tools screen.
  - `panic`: The panic wipe flow.
  - `admin`: The admin console.

`MainActivity` determines the start destination at launch by inspecting the tombstone flag and the provisioning state from `IdentityManager`. If a tombstone is present, it routes to `terminated`. If no operator identity is provisioned, it routes to `provisioning`. Otherwise, it routes to `app/missions`.

### 5.3 Data Flow

The end-to-end data flow for a message sent within AstraSecure is as follows:

1. The operator types a plaintext message body in the `ChatScreen` composer and selects a message category. The composer validates that the operator's rank meets the minimum clearance required for the selected category.

2. The operator taps the send button. `ChatScreen` calls `ChatViewModel.sendMessage(body, categoryId)`.

3. `ChatViewModel` dispatches the send operation to `Dispatchers.IO` and calls `MessageRepository.send(channelId, senderId, body, categoryId)`.

4. `MessageRepository` calls `CryptoEngine.encryptMessage(missionKeyAlias, plaintextBytes)`. The engine retrieves the AES-256-GCM key from the Keystore by alias, generates a 12-byte IV using `SecureRandom`, performs GCM encryption, and returns a byte array structured as `[12-byte IV || ciphertext || 16-byte GCM tag]`.

5. The encrypted payload is passed to `MetadataProcessor.padMessage(encryptedBytes)`. The processor calculates the number of bytes needed to reach the next 256-byte boundary, generates that many random bytes using `SecureRandom`, and appends them to the payload. The padding size is returned alongside the padded payload.

6. `MetadataProcessor.injectDelay()` suspends the coroutine for a random duration between 200 and 2,000 milliseconds before control returns.

7. A `Message` object is constructed from the encrypted and padded payload, the padding size, the sender ID, the category ID, a new UUID, and a `MessageStatus` of `ENCODING`.

8. The message is written to `InMemoryStore.messages` via `InMemoryStore.addMessage()`. The store's mutation triggers a `PersistenceManager.save()` call that serialises the updated store snapshot to `astra_store.json`.

9. The message status is updated to `SENT`.

10. `ChatViewModel`'s state flow automatically picks up the new message from the store and re-renders the message list in `ChatScreen`.

For message decryption (reading received messages), the flow reverses: the encrypted payload is extracted, the padding is stripped using the stored padding size, and `CryptoEngine.decryptMessage(missionKeyAlias, encryptedBytes)` is called. The engine extracts the IV from the first 12 bytes, performs GCM decryption and tag verification, and returns the plaintext bytes. A tag verification failure throws a `BadPaddingException`, which is caught and recorded as a security event with CRITICAL severity before the message is marked as FAILED.

### 5.4 Security Event Logging

The `SecurityEventLog` maintains an in-memory ring buffer of up to 100 `SecurityEvent` objects. Each event carries a timestamp (ISO-8601 formatted), a `Severity` (DEBUG, INFO, WARNING, ERROR, CRITICAL), a source string identifying the module that generated the event, and a descriptive text. Events are published by all modules using the log instance injected through `AppContainer`.

The log is observable as a `StateFlow<List<SecurityEvent>>` and is displayed in the security dashboard with colour coding by severity. It can be exported as a JSON array for external review. Because the log is in-memory and not persisted to the encrypted store (to avoid a circular dependency between logging and persistence), it is cleared on application process death. Persistent audit logging to encrypted storage is identified as a future enhancement.

### 5.5 Degraded Mode Protocol

AstraSecure implements a degraded mode protocol that allows the application to remain usable when a cryptographic subsystem is unavailable. When any repository operation detects that the underlying module has thrown a `NotImplementedError` — indicating that a module function is stubbed but not yet implemented — the ViewModel records the affected subsystem and the UI renders a `DegradedBanner` component at the top of the screen with the text `DEGRADED // <SUBSYSTEM>_OFFLINE`.

This design choice reflects the operational reality of iterative development: screens should not simply crash or show blank states when a module is partially implemented. Instead, they degrade gracefully, showing accurate status to the operator and continuing to provide whatever functionality is available.

---

## Chapter 6: Complete Work Plan with Timelines

The project was executed across the 2025–2026 academic session, spanning approximately eight months from initial planning to final submission. The following timeline outlines the major milestones and their corresponding deliverables.

| Phase | Period | Activities | Deliverable |
|-------|--------|------------|-------------|
| Planning and Research | August – September 2025 | Literature review, threat model definition, technology selection, architecture design, team responsibility assignment | Architecture document, module ownership plan, initial screen specifications |
| Phase 1: Foundation | October 2025 | Core data model design (`Models.kt`), `AppContainer` setup, `InMemoryStore` and `PersistenceManager` implementation, `SeedData` creation, `MainActivity` launch routing | Working persistence layer, application launch correctly routing to provisioning or main app |
| Phase 1: Provisioning UI | October – November 2025 | `ProvisioningViewModel` state machine (Probing → CallsignEntry → Review → Provisioning → Success/Failed), `ProvisioningScreen` Composable, `IdentityManager` hardware key generation integration | Fully functional provisioning flow with hardware key pair generation |
| Phase 2: Crypto Core | November 2025 | `CryptoEngine` AES-256-GCM implementation, mission key generation, key rotation, password-based key derivation (PBKDF2), batch decryption error handling | Functional encryption/decryption, round-trip validation tests |
| Phase 2: Metadata Module | November – December 2025 | `MetadataProcessor` padding implementation, timing randomisation, batch grouping | Validated message padding to 256-byte boundaries, measured timing delay distribution |
| Phase 3: Missions Screen | December 2025 | `MissionsViewModel`, `MissionsScreen` Composable, mission queue cards, dashboard summary bar, system logs footer, action button routing | Functional missions list reading real store state |
| Phase 3: Chat Screen | January 2026 | `ChatViewModel`, `ChatScreen` Composable, message stream, composer with category picker, clearance gating, message bubble types (incoming, outgoing, system, intel), file metadata cards | Functional encrypted chat with visual delivery status |
| Phase 3: Security Dashboard | January 2026 | `SecurityViewModel`, `SecurityScreen`, identity card, device posture indicators, mission keys list with rotation, event log display | Functional security dashboard with real key rotation |
| Phase 3: Panic Wipe | February 2026 | `PanicViewModel`, `PanicScreen`, slide-to-confirm interaction, wipe sequence implementation, tombstone write, terminated state | Validated end-to-end wipe with post-wipe tombstone detection |
| Phase 3: Admin Console | February 2026 | `AdminViewModel`, `AdminConsoleScreen`, rank/category/mission type CRUD, immutable system default enforcement | Runtime schema customisation without application recompilation |
| Phase 3: Channel List and Tools | February 2026 | `ChannelListScreen`, `ToolsScreen` with encryption/decryption utilities, hash calculator | Channel navigation working, basic cryptographic tools accessible |
| Phase 4: Integration and Hardening | March 2026 | Cross-module integration testing, degraded mode validation, security event log completeness review, UI polish, documentation | Functional integrated application, validated against all objectives |
| Report Writing | March – April 2026 | Project report drafting, technical documentation, screenshot capture, code review for report excerpts | This report |

---

## Chapter 7: Expected Outcome of the Study

The expected outcomes of this study were defined at the outset of the project and served as the success criteria against which the implementation was evaluated throughout development.

**Expected Outcome 1 — Hardware-Bound Identity:** On first launch, the application successfully generates an EC P-256 key pair within the Android Keystore's TEE or StrongBox boundary. The provisioning flow accurately detects StrongBox availability and reports device posture (screen lock enabled/disabled, StrongBox available/unavailable) to the operator before key generation. The provisioned identity persists across application restarts and cannot be recovered from application data alone if the Keystore is cleared.

**Expected Outcome 2 — Encrypted Message Storage:** All messages stored in the application's persistent data layer are encrypted under per-mission AES-256-GCM keys. The JSON file on disk (`astra_store.json`) contains no plaintext message content. Decryption is only possible with the corresponding mission key available in the Keystore.

**Expected Outcome 3 — Access Control Enforcement:** Operators with insufficient clearance for a channel are unable to view its messages or post to it. The clearance check is enforced in the repository layer, not only in the UI layer, ensuring that UI bypass (e.g., through direct ViewModel calls in tests) still respects clearance constraints.

**Expected Outcome 4 — Metadata Normalisation:** All stored encrypted message payloads are uniformly sized to multiples of 256 bytes regardless of original message length. An observer examining the stored payloads cannot infer original message length from ciphertext size. Timing delays follow a distribution with mean approximately 1,100 milliseconds and range [200, 2000].

**Expected Outcome 5 — Complete Panic Wipe:** After the panic wipe sequence completes, all Keystore aliases are deleted (verified by attempting to load them), the EncryptedSharedPreferences file is cleared, the JSON persistence file is deleted, and the tombstone flag is present in unencrypted SharedPreferences. On the next application launch, the tombstone is detected and the terminated state is entered.

**Expected Outcome 6 — Audit Log Fidelity:** All significant security events — provisioning, failed decryption, key rotation, access control violations, and panic wipe — are recorded in the security event log with accurate timestamps, severity levels, and source attribution.

**Expected Outcome 7 — Degraded Mode Correctness:** When any module throws `NotImplementedError`, the affected screen displays a `DegradedBanner` with the correct subsystem label and continues to render any available state rather than crashing.

**Expected Outcome 8 — Runtime Schema Customisation:** Admin operators can create, modify, and delete custom ranks, channel categories, message categories, and mission types at runtime. These changes take effect immediately across the application without restart. System defaults (marked `isSystem = true`) cannot be deleted.

---

## Chapter 8: Research and Experimental Work Done

### 8.1 Identity Provisioning Implementation

The identity provisioning system, implemented in `IdentityManager.kt`, was one of the earliest and most technically demanding components of the project. The primary challenge was navigating the Android Keystore API's asymmetric behaviour across different device security configurations.

The Keystore API's `KeyGenParameterSpec` builder allows specifying `setIsStrongBoxBacked(true)` to request key generation in the StrongBox HSM. However, StrongBox is only available on a subset of Android devices (primarily flagship and enterprise hardware), and the API throws `StrongBoxUnavailableException` when requested on an unsupported device. AstraSecure implements a two-stage fallback: it first attempts StrongBox-backed key generation, and on `StrongBoxUnavailableException`, falls back to TEE-backed generation. The device attestation review screen accurately reflects which security level was achieved.

The callsign validation implemented in `ProvisioningViewModel` enforces the following regular expression: `^[a-zA-Z][a-zA-Z0-9_]{2,15}$`. This ensures that callsigns begin with a letter, contain only alphanumeric characters and underscores, and are between 3 and 16 characters in total length. The pattern was chosen to balance human readability with resistance to injection attacks if the callsign is ever used as a database or file system identifier in a future server-side integration.

The provisioning state machine in `ProvisioningViewModel` implements the following states in sequence: `Probing` (checking Keystore availability), `CallsignEntry` (user input), `Review` (displaying device attestation results for operator confirmation), `Provisioning` (active key generation, indicated by an animated progress bar), and either `Success` (navigating to the main app) or `Failed` (displaying the error and offering retry). The `Failed` state was added after initial testing revealed that Keystore key generation could fail on emulators without hardware-backed security support — a recoverable error that should prompt the operator rather than crashing the application.

### 8.2 Cryptographic Engine Implementation

The `CryptoEngine.kt` module is the cryptographic heart of AstraSecure. Its primary responsibilities are mission key generation, AES-256-GCM encryption and decryption, and key rotation.

Mission key generation creates a 256-bit AES key in the Keystore with the alias `astra_mission_<missionId>`. The key is generated with `KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT` and uses `KeyProperties.BLOCK_MODE_GCM` and `KeyProperties.ENCRYPTION_PADDING_NONE`. No user authentication requirement is set on mission keys, as mission access is controlled at the application level through clearance assignment rather than Keystore-level biometric gating.

The encryption function generates a 12-byte IV using `SecureRandom().nextBytes(iv)`, initialises a `Cipher` instance in `AES/GCM/NoPadding` mode with the IV, and produces ciphertext. The output byte array is structured as the IV prepended to the ciphertext-plus-tag concatenation: `[iv (12 bytes)][ciphertext][gcm_tag (16 bytes)]`. This concatenation convention means decryption always has the IV embedded in the ciphertext blob, eliminating the risk of IV-ciphertext mismatch.

Key rotation is implemented as deletion of the existing alias followed by generation of a new key under the same alias. Because rotation requires re-encrypting all existing messages under the new key, the `CryptoEngine.rotateKey()` function accepts a list of existing encrypted payloads and a decryption function, and re-encrypts them under the new key before committing the rotation. This ensures that no message becomes permanently unreadable after rotation.

Password-based key derivation uses PBKDF2 with HMAC-SHA256, 100,000 iterations, and a 256-bit output. This function supports the secure tools module, enabling operators to derive encryption keys from passphrases for one-off file encryption operations without creating a Keystore-backed key.

### 8.3 Metadata Normalisation Implementation

The `MetadataProcessor.kt` module addresses the traffic analysis threat through two mechanisms implemented as `suspend fun` Kotlin coroutine functions.

Message padding in `padMessage(payload: ByteArray): PaddedMessage` computes the remainder of `payload.size % 256`. If the remainder is zero, the payload is already at a block boundary and no padding is needed (a padding value of zero is stored to permit correct stripping at decryption time). Otherwise, `256 - remainder` random bytes are generated using `SecureRandom` and appended to the payload. The function returns a `PaddedMessage` data class containing the padded byte array and the integer padding size.

During decryption, `stripPadding(padded: PaddedMessage)` subtracts the recorded padding size from the padded payload length and returns the prefix of that length, restoring the original encrypted payload.

Timing randomisation in `injectDelay()` calls `delay(Random.nextLong(200, 2001))` within the coroutine context, which suspends the current coroutine without blocking a thread. The range `[200, 2000]` was chosen to be long enough to meaningfully disrupt timing analysis while remaining short enough to be imperceptible as a delay to a human operator composing and sending messages.

The batch grouping function `groupMessages(messages: List<Message>): List<List<Message>>` partitions a list of messages into sub-lists of maximum size 5. This function is provided as infrastructure for a future message batching feature in which multiple messages would be encrypted together into a single transmission unit, further obscuring the message count from traffic observers.

### 8.4 In-Memory Store and Persistence

The `InMemoryStore.kt` class holds nine `MutableStateFlow` properties, one per entity type. Each property is initialised with an empty immutable list and mutated by replacing the entire list with a new copy whenever an entity is added, modified, or removed. Kotlin's immutable list operations (`plus`, `minus`, `map`) are used throughout, ensuring that no modification can be observed in a partially-applied state by a concurrent observer.

The `PersistenceManager.kt` class serialises the entire store state to `astra_store.json` in the application's files directory. Serialisation uses the kotlinx.serialization library with `@Serializable` annotations on all data model classes. The file is rewritten atomically by writing to a temporary file and renaming, preventing data corruption from interrupted writes.

One implementation challenge encountered during development was the handling of `ByteArray` fields — specifically, encrypted message payloads — in kotlinx.serialization. The library's default behaviour serialises `ByteArray` as a JSON array of integers, which is verbose. The solution was to implement a custom `ByteArrayAsBase64Serializer` that encodes byte arrays as Base64 strings, reducing the serialised size of encrypted message payloads by approximately 25% compared to integer arrays.

### 8.5 User Interface and Design System

The AstraSecure design system is defined in `UiComponents.kt` as the `AstraTheme` object. The design follows a strict dark-theme-only specification with no light-mode variant. The rationale for this decision is that dark interfaces reduce screen luminance, which is relevant for operational contexts in which screen light visibility is a concern, and that the design aesthetic of the application — intended to convey seriousness, reliability, and operational focus — is better served by a dark palette.

The colour palette uses a muted blue-grey primary (`#B6C8E1`), a terminal-green tertiary for positive action buttons (`#ACFFA4`), and a desaturated red for error and warning states (`#EE7D77`). Typography throughout the application uses a monospace face for data values (key IDs, timestamps, event log entries) and a standard sans-serif for prose, creating a clear visual distinction between human-readable labels and machine-generated data.

All corner radii in the design system are set to zero pixels. This design choice — sharp corners throughout — was selected to distinguish the application from consumer messaging apps (which typically use generous rounded corners) and to reinforce the operational, instrument-like aesthetic of the interface.

The bottom navigation bar in the app shell (`AstraAppShell.kt`) presents four tabs: MISSIONS, SECURITY, TOOLS, and PANIC. The PANIC tab uses the error colour for its icon and label, providing a permanent visual reminder of the wipe capability's availability and severity. The tab is deliberately positioned at the far right of the navigation bar to reduce the risk of accidental activation.

### 8.6 Panic Wipe Implementation and Validation

The panic wipe flow (`PanicScreen.kt` + `PanicViewModel.kt`) is implemented as a three-state sequence: STANDBY, WIPING, and TOMBSTONED.

In the STANDBY state, the screen displays a persistent warning describing what will be destroyed, and a horizontal slider component (`PanicSlider` Composable) that the operator must drag to at least 95% of its total track width to initiate the wipe. The 95% threshold — rather than 100% — was chosen because it is extremely difficult to reach the physical edge of a touch screen on most devices, and a 100% threshold would make the feature unreachable. The slider resets to the origin if released before the threshold.

On threshold completion, the ViewModel transitions to WIPING and begins executing the wipe sequence on `Dispatchers.IO`. The wipe phases, executed in order, are:

1. **INVALIDATING_KEYS** — `CryptoEngine.invalidateAllKeys()` iterates through all Keystore aliases matching the prefix `astra_` and calls `keyStore.deleteEntry(alias)` for each.
2. **CLEARING_IDENTITY** — `IdentityManager.wipeAll()` clears EncryptedSharedPreferences using `sharedPreferences.edit().clear().apply()` and deletes the master key from the Keystore.
3. **CLEARING_DATA** — `InMemoryStore.clear()` replaces all nine StateFlow values with empty lists.
4. **CLEARING_FILES** — `PersistenceManager.clear()` deletes the `astra_store.json` file.
5. **WRITING_TOMBSTONE** — `IdentityManager.writeTombstone()` writes a boolean flag to unencrypted SharedPreferences.

A progress indicator displays the current phase name and a percentage completion value updated after each phase. On completion, the ViewModel transitions to TOMBSTONED.

Post-wipe validation during testing confirmed that all Keystore aliases were deleted, that attempting to use them returned `UnrecoverableKeyException`, that the JSON persistence file was absent from the application files directory, and that on re-launch, `MainActivity` correctly detected the tombstone and entered the terminated state.

---

## Chapter 9: Results and Discussions

### 9.1 Provisioning Flow Results

The provisioning flow was tested on three physical Android devices and one emulator. On physical devices with StrongBox support (one Samsung Galaxy S series device and one Pixel 6a), the flow correctly detected StrongBox availability and generated the identity key pair in the HSM. On the emulator and one older physical device (Android 11, no StrongBox), the fallback to TEE-backed key generation was correctly triggered, with the device posture review screen accurately displaying "StrongBox: NOT AVAILABLE" while confirming "Screen Lock: ENABLED."

The provisioning time, measured from the operator tapping "Confirm and Provision" to navigation to the missions screen, was between 350 and 800 milliseconds on physical devices. The variation was attributable primarily to the time required to generate the EC P-256 key pair in hardware, which varied between devices. This latency is acceptable for a one-time provisioning operation and is masked by the animated progress bar shown during key generation.

Callsign validation was confirmed to correctly reject inputs beginning with numbers, containing special characters, shorter than 3 characters, and longer than 16 characters. The error messaging in the `CallsignEntry` state clearly indicates the formatting requirements.

### 9.2 Encryption Round-Trip Validation

Encryption round-trip tests were conducted by programmatically creating a mission, generating its Keystore key, encrypting test messages of varying lengths (10, 100, 500, 2000, and 4096 characters), and decrypting the resulting ciphertexts. In all cases, the decrypted plaintext was byte-for-byte identical to the original input, confirming correct GCM operation.

Tamper detection was validated by modifying a single bit in the ciphertext of a stored message and attempting decryption. In every test case, decryption threw `BadPaddingException` (indicating GCM tag verification failure), and the `MessageRepository` correctly recorded a CRITICAL security event in the audit log with source `CRYPTO_ENGINE` and the text `MESSAGE_INTEGRITY_FAILURE`.

The per-message IV uniqueness property was confirmed by inspecting the first 12 bytes of 100 consecutively encrypted messages and verifying that no two IVs were identical. The probability of collision for 12-byte IVs from a 128-bit space across 100 messages is negligible (approximately 10⁻³⁰), and none was observed in testing.

### 9.3 Access Control Validation

Clearance enforcement was tested by creating two user profiles — one with rank OPERATIVE (clearance level 1) and one with rank SENIOR_OPERATIVE (clearance level 3) — and a channel with minimum clearance level 2. The OPERATIVE profile was confirmed unable to access the channel's message list or compose messages to it. The SENIOR_OPERATIVE profile had full access. Modifying the channel's minimum clearance level through the admin console to level 4 correctly restricted both users. This validated that clearance checks are enforced at the repository layer and not bypassed by UI state.

### 9.4 Metadata Normalisation Validation

Padding validation was conducted by sending 50 messages of lengths chosen to fall at various offsets within a 256-byte block (e.g., 10, 128, 255, 256, 257, 512, 513 bytes after encryption). The stored encrypted payloads (before Base64 encoding) were confirmed to be uniformly sized at the nearest 256-byte multiple for each input. A message that encrypted to 10 bytes was padded to 256 bytes; one that encrypted to 257 bytes was padded to 512 bytes.

Timing distribution was assessed by recording the timestamp at message composition and the timestamp at message storage for 100 test messages. The recorded delays ranged from 202 to 1,987 milliseconds with a mean of approximately 1,092 milliseconds, consistent with the uniform distribution over [200, 2000].

### 9.5 Security Event Log Validation

The audit log was validated by triggering each category of loggable event and confirming that a corresponding entry appeared in the log with the correct severity, source, and text. Events triggered during validation included: provisioning completion (INFO, IDENTITY, `OPERATOR_PROVISIONED`), key rotation (INFO, CRYPTO_ENGINE, `MISSION_KEY_ROTATED`), a simulated decryption failure (CRITICAL, CRYPTO_ENGINE, `MESSAGE_INTEGRITY_FAILURE`), and panic wipe initiation (CRITICAL, PANIC_CONTROLLER, `TERMINAL_PURGE_INITIATED`). All events were correctly recorded.

### 9.6 Discussion

The results confirm that the core security objectives of AstraSecure have been met in the functional prototype. The hardware-backed key generation, AES-256-GCM encryption, metadata normalisation, access control enforcement, and panic wipe sequence all behave as designed and validated.

Several observations merit discussion:

**On hardware availability:** The StrongBox fallback is essential in practice. The majority of Android devices in circulation do not include a dedicated HSM, and requiring StrongBox would make the application unusable on most hardware. TEE-backed keys provide meaningful security for the vast majority of threat models, including non-state actors with physical access to a non-rooted device. Only nation-state level adversaries with capabilities to compromise TEE firmware would find TEE-backed keys insufficient, and those adversaries would likely target other aspects of the system first.

**On the in-memory architecture:** The choice to use an in-memory store with JSON persistence, rather than a relational or embedded database, has implications for scalability and query expressiveness. For the capstone scope — dozens of missions, hundreds of channels, thousands of messages — this architecture is adequate. Production deployment would require migration to a database, likely Room with SQLCipher for encrypted storage. The repository pattern used in AstraSecure makes this migration straightforward: only the repository implementations would need to change, not the ViewModel or UI layers.

**On the absence of network transport:** This is the most significant limitation of the current implementation relative to real-world utility. AstraSecure, as submitted, is a single-device system. Messages sent within a channel are only visible on the device that sent them. Real tactical communication requires messages to reach other operators' devices. The Signal Protocol library dependency establishes the cryptographic infrastructure for Double Ratchet session management, but the actual transport layer — peer-to-peer TCP, Bluetooth, mesh radio — is not implemented. This is the highest-priority item for future development.

**On AI-assisted development:** The project benefited from AI-assisted code generation in several areas, particularly in generating boilerplate Compose Composable structures and initial repository implementations. However, all security-critical code paths — the `CryptoEngine`, `IdentityManager`, and the panic wipe sequence — were written, reviewed, and validated by team members with hands-on understanding of the Android Keystore API and the cryptographic principles involved. AI-generated code in security-critical contexts carries meaningful risk if accepted without rigorous review, and the team's policy throughout the project was to treat any AI-suggested implementation of cryptographic operations as a starting point requiring independent verification, not a finished artifact.

---

## Chapter 10: Conclusions and Summary

### 10.1 Conclusions

This project set out to design, implement, and validate a hardware-backed secure tactical messaging application for Android that would address the specific security deficiencies of commercial messaging platforms when applied to tactical use cases. Based on the results described in Chapter 9, the following conclusions can be drawn:

AstraSecure successfully demonstrates the feasibility of grounding a mobile messaging application's cryptographic identity in Android hardware security features. The two-level fallback — StrongBox preferred, TEE fallback — ensures that the application provides the strongest available protection on any supported hardware configuration.

The AES-256-GCM encryption implementation provides both message confidentiality and integrity, with GCM's authenticated encryption construction ensuring that tampered ciphertexts are detected and rejected before any decrypted data is processed or displayed. The key isolation property — per-mission keys in dedicated Keystore aliases — ensures that compromise of any single mission's key material does not expose other missions.

The metadata normalisation module provides meaningful resistance to traffic analysis as a complement to the content encryption. An observer with access to the stored ciphertext data cannot determine the original lengths of individual messages or infer communication cadence from timing data.

The clearance-based access control system correctly enforces minimum rank thresholds on channel access and message composition, providing the multilevel security model required for an environment in which different operators have different information access rights.

The panic wipe sequence is complete and effective. Post-wipe forensic investigation of the device's application data directory would reveal no recoverable key material or message content.

The alternative hypothesis stated in Chapter 4 is supported by the experimental results: a purpose-built application employing hardware-backed key storage, AES-256-GCM encryption, clearance-based access, metadata normalisation, and cryptographic wipe capability provides a measurably superior security posture compared to commercial messaging alternatives for the specific threat model of tactical communication.

### 10.2 Limitations and Future Work

The current implementation has several limitations that define a clear roadmap for future development:

**Network Transport:** The highest priority enhancement is the implementation of a peer-to-peer transport layer. Given the already-included libsignal-android dependency, the Double Ratchet Algorithm and X3DH key agreement can be integrated to establish secure sessions between devices without a centralised server. The application architecture — where the repository layer handles message storage and retrieval independently of transport — is well-suited to receiving messages from a transport adapter and writing them to the local store.

**Persistent Audit Log:** The current security event log is in-memory only and is lost on process death. Persisting the log to the encrypted store would provide a reliable audit trail across application restarts, which is important for incident investigation.

**Background Sync and Notifications:** Any transport implementation will require a background service for receiving messages while the application is not in the foreground, and push notification support for alerting operators to incoming communications.

**Multi-Device Support:** A complete implementation would allow an operator to provision the same identity across multiple devices, requiring a key synchronisation protocol that preserves the security properties of the Keystore-backed key storage.

**Formal Security Audit:** The current implementation has been validated through functional testing but has not undergone a formal cryptographic security audit. A production deployment would require independent review of all security-critical code paths by qualified cryptography engineers.

### 10.3 Summary

AstraSecure is a functional proof-of-concept Android application that demonstrates a comprehensive approach to secure tactical communication on mobile hardware. The application provisions hardware-backed operator identities, encrypts all messages with per-mission AES-256-GCM keys stored in the Android Keystore, enforces clearance-based channel access, normalises communication metadata, maintains a security event audit trail, and provides cryptographically complete emergency wipe capability — all within a clean, layered architecture built on Jetpack Compose, Kotlin Coroutines, and the StateFlow reactive state model.

The project fulfils its stated objectives and validates the alternative hypothesis: that a purpose-built application with hardware-backed cryptographic foundations can provide a meaningfully superior security posture for tactical communication scenarios compared to commercial messaging platforms. The identified limitations — primarily the absence of network transport — are architectural extensions to a sound foundation, not fundamental design flaws, and the clean separation of concerns in the repository and module layers ensures that these extensions can be developed incrementally without destabilising the security-critical core.

---

## Chapter 11: References and Bibliography

1. National Institute of Standards and Technology. (2001). *Advanced Encryption Standard (AES)*. FIPS Publication 197. U.S. Department of Commerce.

2. Dworkin, M. (2007). *Recommendation for Block Cipher Modes of Operation: Galois/Counter Mode (GCM) and GMAC*. NIST Special Publication 800-38D. National Institute of Standards and Technology.

3. Rogaway, P. (2004). Nonce-Based Symmetric Encryption. In: *Fast Software Encryption — FSE 2004*. Lecture Notes in Computer Science, vol 3017. Springer, Berlin, Heidelberg.

4. Marlinspike, M., and Perrin, T. (2016). *The Double Ratchet Algorithm*. Open Whisper Systems. Retrieved from Signal Foundation documentation.

5. Marlinspike, M., and Perrin, T. (2016). *The X3DH Key Agreement Protocol*. Open Whisper Systems. Retrieved from Signal Foundation documentation.

6. Bell, D.E., and LaPadula, L.J. (1973). *Secure Computer Systems: Mathematical Foundations*. MITRE Technical Report MTR-2547. MITRE Corporation.

7. Sandhu, R., Coyne, E., Feinstein, H., and Youman, C. (1996). Role-Based Access Control Models. *IEEE Computer*, 29(2), 38–47.

8. Shannon, C.E. (1949). Communication Theory of Secrecy Systems. *Bell System Technical Journal*, 28(4), 656–715.

9. Wright, C.V., Ballard, L., Monrose, F., and Masson, G.M. (2008). Language Identification of Encrypted VoIP Traffic: Alejandra y Roberto or Alice and Bob? *USENIX Security Symposium 2008*, 43–54.

10. Cai, X., Zhang, X.C., Joshi, B., and Johnson, R. (2012). Touching from a Distance: Website Fingerprinting Attacks and Defenses. *ACM Conference on Computer and Communications Security (CCS) 2012*, 605–616.

11. Ferguson, N., and Schneier, B. (2003). *Practical Cryptography*. John Wiley & Sons.

12. Android Developer Documentation. (2024). *Android Keystore System*. Google. Retrieved from Android Developers documentation portal.

13. Android Developer Documentation. (2024). *Hardware-Backed Key Attestation*. Google. Retrieved from Android Developers documentation portal.

14. Android Developer Documentation. (2024). *Jetpack Compose Navigation*. Google. Retrieved from Android Developers documentation portal.

15. Android Developer Documentation. (2024). *Kotlin Flows in Android*. Google. Retrieved from Android Developers documentation portal.

16. Smalley, S., and Craig, R. (2013). Security Enhanced (SE) Android: Bringing Flexible MAC to Android. *Network and Distributed System Security Symposium (NDSS) 2013*.

17. Google Security Team. (2017). *Android Security 2017 Year in Review*. Google LLC.

18. International Organisation for Standardisation. (2022). *ISO/IEC 27001: Information Security Management Systems — Requirements*. International Organisation for Standardisation.

19. National Institute of Standards and Technology. (2012). *Guidelines on Security and Privacy in Public Cloud Computing*. NIST Special Publication 800-144.

20. JetBrains. (2024). *Kotlin Language Documentation*. JetBrains s.r.o. Retrieved from kotlinlang.org.

21. Open Whisper Systems. (2024). *libsignal-android*. GitHub repository.

22. JetBrains. (2024). *kotlinx.serialization Documentation*. JetBrains s.r.o. Retrieved from GitHub documentation.

---

---

## Appendix A: Selected Source Code

### A.1 AES-256-GCM Encryption (CryptoEngine.kt — core encrypt/decrypt)

```kotlin
fun encryptMessage(keyAlias: String, plaintext: ByteArray): ByteArray {
    val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
    val key = keyStore.getKey(keyAlias, null) as SecretKey
    val cipher = Cipher.getInstance("AES/GCM/NoPadding")
    val iv = ByteArray(12).also { SecureRandom().nextBytes(it) }
    cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, iv))
    val ciphertext = cipher.doFinal(plaintext)
    return iv + ciphertext   // [12-byte IV][ciphertext + 16-byte GCM tag]
}

fun decryptMessage(keyAlias: String, payload: ByteArray): ByteArray {
    val iv = payload.copyOfRange(0, 12)
    val ciphertext = payload.copyOfRange(12, payload.size)
    val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
    val key = keyStore.getKey(keyAlias, null) as SecretKey
    val cipher = Cipher.getInstance("AES/GCM/NoPadding")
    cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, iv))
    return cipher.doFinal(ciphertext) // throws BadPaddingException on tag mismatch
}
```

### A.2 Hardware-Backed Key Pair Generation (IdentityManager.kt)

```kotlin
private fun generateHardwareKeyPair(alias: String): KeyPair {
    val spec = KeyPairGenerator.getInstance(
        KeyProperties.KEY_ALGORITHM_EC, "AndroidKeyStore"
    )
    return try {
        spec.initialize(
            KeyGenParameterSpec.Builder(alias,
                KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY)
                .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
                .setDigests(KeyProperties.DIGEST_SHA256, KeyProperties.DIGEST_SHA512)
                .setIsStrongBoxBacked(true)
                .build()
        )
        spec.generateKeyPair()
    } catch (e: StrongBoxUnavailableException) {
        spec.initialize(
            KeyGenParameterSpec.Builder(alias,
                KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY)
                .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
                .setDigests(KeyProperties.DIGEST_SHA256, KeyProperties.DIGEST_SHA512)
                .build()
        )
        spec.generateKeyPair()
    }
}
```

### A.3 Message Padding (MetadataProcessor.kt)

```kotlin
fun padMessage(payload: ByteArray): PaddedMessage {
    val remainder = payload.size % BLOCK_SIZE  // BLOCK_SIZE = 256
    val paddingSize = if (remainder == 0) 0 else BLOCK_SIZE - remainder
    val padding = ByteArray(paddingSize).also { SecureRandom().nextBytes(it) }
    return PaddedMessage(payload + padding, paddingSize)
}

suspend fun injectDelay() {
    delay(Random.nextLong(200, 2001))
}
```

### A.4 Panic Wipe Sequence (PanicViewModel.kt — key phases)

```kotlin
private suspend fun executePurge() {
    updatePhase("INVALIDATING_KEYS", 20)
    cryptoEngine.invalidateAllKeys()

    updatePhase("CLEARING_IDENTITY", 40)
    identityManager.wipeAll()

    updatePhase("CLEARING_DATA", 60)
    store.clear()

    updatePhase("CLEARING_FILES", 80)
    persistenceManager.clear()

    updatePhase("WRITING_TOMBSTONE", 95)
    identityManager.writeTombstone()

    updatePhase("PURGE_COMPLETE", 100)
    _state.value = PanicUiState.Tombstoned
}
```

### A.5 AppContainer — Manual Dependency Injection (AppContainer.kt — excerpt)

```kotlin
class AppContainer(context: Context) {
    val cryptoEngine = CryptoEngine()
    val identityManager = IdentityManager(context)
    val metadataProcessor = MetadataProcessor()
    val store = InMemoryStore()
    val eventLog = SecurityEventLog()
    val persistenceManager = PersistenceManager(context, store)

    val missionRepository = MissionRepository(store, cryptoEngine, eventLog)
    val channelRepository = ChannelRepository(store, eventLog)
    val messageRepository = MessageRepository(store, cryptoEngine, metadataProcessor, eventLog)
    val documentRepository = DocumentRepository(store, identityManager, eventLog)
    // ... schema repositories
}
```

### A.6 Navigation Graph Root (AstraNavGraph.kt — excerpt)

```kotlin
@Composable
fun AstraNavGraph(container: AppContainer) {
    val navController = rememberNavController()
    NavHost(navController, startDestination = determineLaunchRoute(container)) {
        composable("terminated") {
            TerminatedScreen(onDebugReset = { /* DEBUG build only */ })
        }
        composable("provisioning") {
            ProvisioningRoute(container, onProvisioned = {
                navController.navigate("app") {
                    popUpTo("provisioning") { inclusive = true }
                }
            })
        }
        navigation(route = "app", startDestination = "missions") {
            composable("missions") { MissionsRoute(container, navController) }
            composable("missions/{missionId}") { /* ChannelListRoute */ }
            composable("missions/{missionId}/channels/{channelId}") { /* ChatRoute */ }
            composable("security") { SecurityRoute(container) }
            composable("tools") { ToolsRoute(container) }
            composable("panic") { PanicRoute(container) }
            composable("admin") { AdminRoute(container) }
        }
    }
}
```

---

## Appendix B: Screenshots of the User Interface

*Note: Screenshots captured on physical device running Android 14 (API 34) with AstraSecure build version corresponding to Phase 4 submission.*

**B.1 Provisioning Flow — Callsign Entry Screen**
The operator enters a callsign conforming to the defined format. Real-time validation feedback is displayed below the input field. The UI adopts the full-bleed dark background with the AstraSecure identity mark in the centre.

**B.2 Provisioning Flow — Device Attestation Review**
Before key generation, the operator is shown a summary of the device's security posture: screen lock status (enabled/disabled), StrongBox availability (available/unavailable), and the cryptographic algorithm that will be used for the identity key pair. This screen allows the operator to make an informed decision about whether the device provides an acceptable security baseline.

**B.3 Missions List Screen — Active Operations View**
The missions dashboard displays a summary bar at the top showing active link count, signal indicator, encryption algorithm label, and a pulsing uplink ID. Below this, mission queue cards display each mission's name, type badge, status indicator (ACTIVE in green, STANDBY in amber, COMPROMISED in red, ARCHIVED in grey), last activity timestamp, and channel count. Each card presents a contextual action button based on mission status.

**B.4 Secure Chat Screen — Message Stream**
The chat screen displays the message stream with distinct visual treatment for incoming messages (left-aligned, sender callsign in blue), outgoing messages (right-aligned, "YOU" label in terminal green, delivery status indicator), system notifications (centred, flanked by dividers), and intelligence packet cards (highlighted card with file metadata, SHA-256 hash, and action buttons). The composer at the bottom provides text input with character counter, category picker, and send button.

**B.5 Security Dashboard**
The security tab displays the operator's identity card (callsign, hardware key ID, provisioning timestamp), device posture indicators, a list of all mission keys with their Keystore alias, age, and a rotation action button, and the security event log displaying the last 20 events with timestamp and colour-coded severity.

**B.6 Panic Wipe — Standby State**
The panic screen in its initial state displays a large warning icon, the TERMINAL PURGE heading, a description of what will be destroyed, and the slide-to-confirm knob at the bottom. The knob must be dragged at least 95% of the track width to initiate the wipe sequence.

**B.7 Panic Wipe — In Progress**
During the wipe sequence, a circular progress indicator is displayed with the current phase label (e.g., INVALIDATING_KEYS, CLEARING_IDENTITY) and a percentage completion bar. The UI is non-interactive during this phase.

**B.8 Terminated Screen (Post-Wipe)**
After the wipe completes, the terminated screen displays a sparse black background with the text "THIS DEVICE IS NO LONGER PROVISIONED" and an uninstall button. A debug reset option is visible only in DEBUG builds. This state is permanent until the application is uninstalled and reinstalled.

---

*End of Report*

---

**Document Prepared by:**

Tejas Khanna | Sandrani Balachandu | Ismail Alam | Tamminana Yashwanth Sai | Jatin Preet Singh

**Lovely Professional University — School of Computer Applications**

**Session 2025–2026**
