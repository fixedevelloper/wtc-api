/**
 * <b>ETL de migration</b> (phase 10) : copie les données de l'ancien backend Symfony (MySQL) vers
 * le nouveau schéma. Code d'exploitation à usage unique : actif uniquement avec le profil Spring
 * {@code etl}, jamais dans l'API en production. Spécification : {@code
 * docs/design/data-migration-mapping.md}.
 */
package com.wtc.migration;
