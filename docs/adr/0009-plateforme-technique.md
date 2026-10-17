# ADR 0009 — Plateforme technique : Java 21 et Spring Boot 4.1

- **Statut** : Accepté (mis en œuvre en phase 1)
- **Date** : 2026-10-16

## Contexte
Le projet démarre en octobre 2026. La branche Spring Boot 3.5 n'est plus maintenue en open source ; la ligne supportée est **4.1** (Spring Framework 7, Spring Security 7, Hibernate 7, Jackson 3, Flyway 12, Testcontainers 2). Choisir une version déjà hors support créerait immédiatement une dette de montée de version.

## Décision
- **Java 21** (LTS) et **Spring Boot 4.1.1**, Maven (wrapper fourni : `./mvnw`).
- Jackson 3 (`tools.jackson`) pour l'application. Jackson 2 n'est présent qu'en **test**, pour valider les réponses contre le contrat (bibliothèque JSON Schema).
- Les mappings JSON Hibernate sont évités : les colonnes `jsonb` sont lues par `JdbcClient` et désérialisées explicitement.
- Les tests d'intégration utilisent **Testcontainers PostgreSQL 16** (aucune base embarquée type H2).
- Hibernate en `ddl-auto=validate` : **Flyway est le seul propriétaire du schéma**, Hibernate vérifie seulement que les entités correspondent.

## Conséquences
+ Plateforme supportée plusieurs années ; API de sécurité et de test à jour.
− Écosystème plus jeune : certaines bibliothèques tierces n'ont pas encore migré vers Jackson 3 / Spring 7, d'où des exceptions ciblées (ci-dessus).
− Les noms de packages de test ont changé par rapport à Spring Boot 3 (par exemple `org.springframework.boot.webmvc.test.autoconfigure`).

## Écarts assumés par rapport à l'ADR 0007 (état de la phase 1)
Mis en place : Spotless (google-java-format AOSP), SpotBugs + Find Security Bugs, JaCoCo (seuil de 80 % des lignes), ArchUnit, Enforcer, Dependabot, CodeQL, Trivy, Gitleaks, OWASP Dependency-Check (hebdomadaire).
**Reportés** : Checkstyle/PMD (le format et SpotBugs couvrent l'essentiel pour l'instant), SonarQube (demande un serveur ou un compte à choisir), tests de mutation PIT et tests de propriétés (à introduire avec le cœur financier, phase 5).
