# ADR 0001 — Monolithe modulaire en architecture hexagonale

- **Statut** : Proposé (basé sur les valeurs par défaut de [assumptions.md](../design/assumptions.md), à confirmer)
- **Date** : 2026-10-16

## Contexte
Le backend Symfony actuel mélange accès aux données, règles métier et HTTP dans des contrôleurs de plus de 1 500 lignes ; la même logique existait en 4 variantes (`/v1`, `/v2`, `/v3`, `/api`). L'équipe est petite et le volume très faible (16 transactions en décembre 2025).

## Décision
Une seule application déployable, découpée en **modules métier** : `identity`, `customer`, `reference`, `wallet`, `transfer`, `payment`, `backoffice`, `notification`, `shared`. Chaque module est organisé en couches `api` (contrôleurs, DTO) → `application` (cas d'usage) → `domain` (entités, objets-valeurs, règles) ← `infrastructure` (JPA, clients HTTP).

- Le **domaine ne dépend ni de Spring, ni de JPA, ni de Wace**.
- Les dépendances entre modules passent par des interfaces publiques ; elles sont **vérifiées automatiquement** (ArchUnit / Spring Modulith) et font échouer la CI.
- Java 21 ; `record` pour les DTO et objets-valeurs immuables.

## Conséquences
+ Un seul déploiement et une seule base : simple à exploiter, transactions locales (indispensables au grand livre).
+ Frontières nettes : un module peut devenir un service plus tard si le besoin apparaît.
− Discipline nécessaire : les règles d'architecture doivent être testées, pas seulement documentées.

## Alternatives écartées
Microservices (surcoût d'exploitation et transactions distribuées injustifiés pour ce volume) ; MVC en couches classiques par type technique (reproduit le couplage actuel).
