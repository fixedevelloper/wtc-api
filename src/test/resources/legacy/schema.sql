
/*!40101 SET @OLD_CHARACTER_SET_CLIENT=@@CHARACTER_SET_CLIENT */;
/*!40101 SET @OLD_CHARACTER_SET_RESULTS=@@CHARACTER_SET_RESULTS */;
/*!40101 SET @OLD_COLLATION_CONNECTION=@@COLLATION_CONNECTION */;
/*!50503 SET NAMES utf8mb4 */;
/*!40103 SET @OLD_TIME_ZONE=@@TIME_ZONE */;
/*!40103 SET TIME_ZONE='+00:00' */;
/*!40014 SET @OLD_UNIQUE_CHECKS=@@UNIQUE_CHECKS, UNIQUE_CHECKS=0 */;
/*!40014 SET @OLD_FOREIGN_KEY_CHECKS=@@FOREIGN_KEY_CHECKS, FOREIGN_KEY_CHECKS=0 */;
/*!40101 SET @OLD_SQL_MODE=@@SQL_MODE, SQL_MODE='NO_AUTO_VALUE_ON_ZERO' */;
/*!40111 SET @OLD_SQL_NOTES=@@SQL_NOTES, SQL_NOTES=0 */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `analysemarge` (
  `id` int NOT NULL AUTO_INCREMENT,
  `datetransaction` datetime NOT NULL,
  `numerotransaction` varchar(255) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `customer` varchar(255) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `montanttotal` double DEFAULT NULL,
  `montant` double DEFAULT NULL,
  `fraisenvoi` double DEFAULT NULL,
  `montantfrais` double DEFAULT NULL,
  `tauxjours` double DEFAULT NULL,
  `maxicashusd` double DEFAULT NULL,
  `maxicashxaf` double DEFAULT NULL,
  `marge` double DEFAULT NULL,
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `balance_customer` (
  `id` int NOT NULL AUTO_INCREMENT,
  `country_id` int DEFAULT NULL,
  `customer_id` int DEFAULT NULL,
  `solde` double NOT NULL,
  PRIMARY KEY (`id`),
  KEY `IDX_AAB93FB9F92F3E70` (`country_id`),
  KEY `IDX_AAB93FB99395C3F3` (`customer_id`),
  CONSTRAINT `FK_AAB93FB99395C3F3` FOREIGN KEY (`customer_id`) REFERENCES `customer` (`id`),
  CONSTRAINT `FK_AAB93FB9F92F3E70` FOREIGN KEY (`country_id`) REFERENCES `country` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `cash_pickup` (
  `id` int NOT NULL AUTO_INCREMENT,
  `customer_id` int DEFAULT NULL,
  `beneficiare_id` int DEFAULT NULL,
  `country_id` int DEFAULT NULL,
  `town_id` int DEFAULT NULL,
  `amount_to_paid` double DEFAULT NULL,
  `reference_id` varchar(255) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `comment` varchar(255) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `operator` varchar(255) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `origin_fund` varchar(255) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `relaction` varchar(255) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `reason` varchar(255) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `mobile_receive_number` varchar(255) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  PRIMARY KEY (`id`),
  KEY `IDX_73756B859395C3F3` (`customer_id`),
  KEY `IDX_73756B85F6054787` (`beneficiare_id`),
  KEY `IDX_73756B85F92F3E70` (`country_id`),
  KEY `IDX_73756B8575E23604` (`town_id`),
  CONSTRAINT `FK_73756B8575E23604` FOREIGN KEY (`town_id`) REFERENCES `town` (`id`),
  CONSTRAINT `FK_73756B859395C3F3` FOREIGN KEY (`customer_id`) REFERENCES `customer` (`id`),
  CONSTRAINT `FK_73756B85F6054787` FOREIGN KEY (`beneficiare_id`) REFERENCES `contactcustomer` (`id`),
  CONSTRAINT `FK_73756B85F92F3E70` FOREIGN KEY (`country_id`) REFERENCES `country` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `configuration` (
  `id` int NOT NULL AUTO_INCREMENT,
  `tauxplatform` double DEFAULT NULL,
  `phone` varchar(255) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `infobip_app_id` varchar(255) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `infobip_message_id` varchar(255) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `contactcustomer` (
  `id` int NOT NULL AUTO_INCREMENT,
  `firstname` varchar(255) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `lastname` varchar(255) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `email` varchar(255) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `phone` varchar(255) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `bussinesname` varchar(255) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `bankname` varchar(255) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `bankroutingnumber` varchar(255) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `bankaccountnumber` varchar(255) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `banktype` varchar(255) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `bankid` varchar(255) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `customer_id` int DEFAULT NULL,
  `bankiban` varchar(255) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `bankifsccode` varchar(255) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `bankswiftcode` varchar(255) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `bankrelaction` varchar(255) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `banknationalite` varchar(255) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `bankbranchnumber` varchar(255) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `banksignature` varchar(255) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `bankadressephysique` varchar(255) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `recipienteralt` int DEFAULT NULL,
  `id_number` varchar(255) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `id_type` varchar(255) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `beneficiary_code` varchar(255) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `dob` varchar(255) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `zipcode` varchar(255) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `expired_document` varchar(255) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `country_iso_code` varchar(255) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `bussinestype` varchar(255) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `type` varchar(255) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `regiter_business_date` varchar(255) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  PRIMARY KEY (`id`),
  KEY `IDX_6F5E4EF59395C3F3` (`customer_id`),
  CONSTRAINT `FK_6F5E4EF59395C3F3` FOREIGN KEY (`customer_id`) REFERENCES `customer` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `country` (
  `id` int NOT NULL AUTO_INCREMENT,
  `zone_id` int DEFAULT NULL,
  `flag` varchar(255) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `code` varchar(255) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `libelle` varchar(255) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `monaire` varchar(255) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `code_string` varchar(255) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `operateur` json DEFAULT NULL,
  `payer_code` varchar(255) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  PRIMARY KEY (`id`),
  KEY `IDX_5373C9669F2C3FAB` (`zone_id`),
  CONSTRAINT `FK_5373C9669F2C3FAB` FOREIGN KEY (`zone_id`) REFERENCES `zone` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `crypto_monnaie` (
  `id` int NOT NULL AUTO_INCREMENT,
  `taux` double DEFAULT NULL,
  `taux_sell` double DEFAULT NULL,
  `price` double NOT NULL,
  `name` varchar(255) COLLATE utf8mb4_unicode_ci NOT NULL,
  `status` tinyint(1) NOT NULL,
  `rank` varchar(255) COLLATE utf8mb4_unicode_ci NOT NULL,
  `icon_url` varchar(255) COLLATE utf8mb4_unicode_ci NOT NULL,
  `symbole` varchar(255) COLLATE utf8mb4_unicode_ci NOT NULL,
  `code` varchar(255) COLLATE utf8mb4_unicode_ci NOT NULL,
  `change` varchar(255) COLLATE utf8mb4_unicode_ci NOT NULL,
  `btc_price` varchar(255) COLLATE utf8mb4_unicode_ci NOT NULL,
  `market_cap` varchar(255) COLLATE utf8mb4_unicode_ci NOT NULL,
  `created_at` datetime NOT NULL,
  `updated_at` datetime NOT NULL,
  `reseau` json NOT NULL,
  PRIMARY KEY (`id`),
  CONSTRAINT `crypto_monnaie_chk_1` CHECK (json_valid(`reseau`))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `customer` (
  `id` int NOT NULL AUTO_INCREMENT,
  `firstname` varchar(255) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `lastname` varchar(255) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `email` varchar(255) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `nationalite` varchar(255) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `bussinesname` varchar(255) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `motif` varchar(255) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `typeidentification` varchar(255) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `numeropiece` varchar(255) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `isverify` tinyint(1) DEFAULT NULL,
  `compte_id` int DEFAULT NULL,
  `phone` varchar(255) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `country_id` int DEFAULT NULL,
  `fileimagename` varchar(255) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `piecefacefile` varchar(255) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `piecerectofile` varchar(255) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `expireddatepiece` datetime DEFAULT NULL,
  `customeralt` int DEFAULT NULL,
  `city` varchar(255) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `id_type` varchar(255) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `date_of_birth` varchar(255) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `address` varchar(255) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `sender_code` varchar(255) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `balance` double DEFAULT NULL,
  `isagent` tinyint(1) DEFAULT NULL,
  `civility` varchar(255) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `occupation` varchar(255) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `gender` varchar(255) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `partner_id` int DEFAULT NULL,
  `bussinestype` varchar(255) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `type` varchar(255) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `regiter_business_date` varchar(255) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `UNIQ_81398E09F2C56620` (`compte_id`),
  KEY `IDX_81398E09F92F3E70` (`country_id`),
  CONSTRAINT `FK_81398E09F2C56620` FOREIGN KEY (`compte_id`) REFERENCES `user` (`id`),
  CONSTRAINT `FK_81398E09F92F3E70` FOREIGN KEY (`country_id`) REFERENCES `country` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `doctrine_migration_versions` (
  `version` varchar(191) COLLATE utf8mb3_unicode_ci NOT NULL,
  `executed_at` datetime DEFAULT NULL,
  `execution_time` int DEFAULT NULL,
  PRIMARY KEY (`version`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb3 COLLATE=utf8mb3_unicode_ci;
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `employe` (
  `id` int NOT NULL AUTO_INCREMENT,
  `compte_id` int DEFAULT NULL,
  `name` varchar(255) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `UNIQ_F804D3B9F2C56620` (`compte_id`),
  CONSTRAINT `FK_F804D3B9F2C56620` FOREIGN KEY (`compte_id`) REFERENCES `user` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `grilletarifaire` (
  `id` int NOT NULL AUTO_INCREMENT,
  `tranche_a` double DEFAULT NULL,
  `tranche_b` double DEFAULT NULL,
  `frais` double DEFAULT NULL,
  `zone_id` int DEFAULT NULL,
  PRIMARY KEY (`id`),
  KEY `IDX_DBEEC5E99F2C3FAB` (`zone_id`),
  CONSTRAINT `FK_DBEEC5E99F2C3FAB` FOREIGN KEY (`zone_id`) REFERENCES `zone` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `login_audit` (
  `id` int NOT NULL AUTO_INCREMENT,
  `user_id` int DEFAULT NULL,
  `email` varchar(180) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `ip_address` varchar(45) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `success` tinyint(1) NOT NULL,
  `message` varchar(255) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `created_at` datetime NOT NULL COMMENT '(DC2Type:datetime_immutable)',
  PRIMARY KEY (`id`),
  KEY `IDX_946838EDA76ED395` (`user_id`),
  CONSTRAINT `FK_946838EDA76ED395` FOREIGN KEY (`user_id`) REFERENCES `user` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `operateur` (
  `id` int NOT NULL AUTO_INCREMENT,
  `country_id` int DEFAULT NULL,
  `name` varchar(255) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `payer_code` varchar(255) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `shortcode` varchar(255) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `gateway` varchar(255) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `bank_id` varchar(255) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `payer_service` varchar(255) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `is_mobil` tinyint(1) DEFAULT NULL,
  `payer_name` varchar(255) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  PRIMARY KEY (`id`),
  KEY `IDX_B4B7F99DF92F3E70` (`country_id`),
  CONSTRAINT `FK_B4B7F99DF92F3E70` FOREIGN KEY (`country_id`) REFERENCES `country` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `partner` (
  `id` int NOT NULL AUTO_INCREMENT,
  `name` varchar(255) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `wtc_code_sanbox` varchar(255) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `wtc_code_prod` varchar(255) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `balance` double NOT NULL,
  `balance_sandbox` double NOT NULL,
  `active` tinyint(1) NOT NULL,
  `phone` varchar(255) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `email` varchar(255) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `password` varchar(255) COLLATE utf8mb4_unicode_ci NOT NULL,
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `prefunding` (
  `id` int NOT NULL AUTO_INCREMENT,
  `country_id` int DEFAULT NULL,
  `customer_id` int DEFAULT NULL,
  `amount` double NOT NULL,
  `description` longtext COLLATE utf8mb4_unicode_ci,
  `mode` varchar(255) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `dateprefund` datetime DEFAULT NULL,
  `status` varchar(255) COLLATE utf8mb4_unicode_ci NOT NULL,
  `numero` varchar(255) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `image` varchar(255) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `crypto_monaire_id` int DEFAULT NULL,
  `total` double DEFAULT NULL,
  `quantity_crpto` varchar(255) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  PRIMARY KEY (`id`),
  KEY `IDX_1DFCA6CAF92F3E70` (`country_id`),
  KEY `IDX_1DFCA6CA9395C3F3` (`customer_id`),
  KEY `IDX_1DFCA6CA96A81B01` (`crypto_monaire_id`),
  CONSTRAINT `FK_1DFCA6CA9395C3F3` FOREIGN KEY (`customer_id`) REFERENCES `customer` (`id`),
  CONSTRAINT `FK_1DFCA6CA96A81B01` FOREIGN KEY (`crypto_monaire_id`) REFERENCES `crypto_monnaie` (`id`),
  CONSTRAINT `FK_1DFCA6CAF92F3E70` FOREIGN KEY (`country_id`) REFERENCES `country` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `refresh_token` (
  `id` int NOT NULL AUTO_INCREMENT,
  `user_id` int DEFAULT NULL,
  `token` varchar(255) COLLATE utf8mb4_unicode_ci NOT NULL,
  `expires_at` datetime NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `UNIQ_C74F21955F37A13B` (`token`),
  KEY `IDX_C74F2195A76ED395` (`user_id`),
  CONSTRAINT `FK_C74F2195A76ED395` FOREIGN KEY (`user_id`) REFERENCES `user` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `tauxechange` (
  `id` int NOT NULL AUTO_INCREMENT,
  `code` varchar(255) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `symbole` varchar(255) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `montant` double DEFAULT NULL,
  `montant2` double DEFAULT NULL,
  `zone_id` int DEFAULT NULL,
  PRIMARY KEY (`id`),
  KEY `IDX_F6C3CFAD9F2C3FAB` (`zone_id`),
  CONSTRAINT `FK_F6C3CFAD9F2C3FAB` FOREIGN KEY (`zone_id`) REFERENCES `zone` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `town` (
  `id` int NOT NULL AUTO_INCREMENT,
  `libelle` varchar(255) COLLATE utf8mb4_unicode_ci NOT NULL,
  `code` varchar(255) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `country_id` int DEFAULT NULL,
  PRIMARY KEY (`id`),
  KEY `IDX_4CE6C7A4F92F3E70` (`country_id`),
  CONSTRAINT `FK_4CE6C7A4F92F3E70` FOREIGN KEY (`country_id`) REFERENCES `country` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `transaction` (
  `id` int NOT NULL AUTO_INCREMENT,
  `customer_id` int DEFAULT NULL,
  `beneficiare_id` int DEFAULT NULL,
  `montant` double DEFAULT NULL,
  `modetransfert` varchar(255) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `branche` varchar(255) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `typeservice` varchar(255) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `prestateurservice` varchar(255) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `fraisenvoi` double DEFAULT NULL,
  `montanttotal` double DEFAULT NULL,
  `datetransaction` datetime DEFAULT NULL,
  `raisontransaction` longtext COLLATE utf8mb4_unicode_ci,
  `numerotransaction` varchar(255) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `typetransaction` varchar(255) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `country_id` int DEFAULT NULL,
  `status` varchar(255) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `numeroidentifiant` varchar(255) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `fileimagename` varchar(255) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `isdelete` tinyint(1) DEFAULT NULL,
  `partenairenumero` varchar(255) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `town_id` int DEFAULT NULL,
  `operateur` varchar(255) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `business_type` varchar(255) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `origin_fonds` varchar(255) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `agent` varchar(255) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `relaction` varchar(255) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `partner_id` int DEFAULT NULL,
  `bank_iban` varchar(255) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `bank_account_number` varchar(255) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `bank_swift` varchar(255) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `bank_id` varchar(255) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `comment` varchar(255) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  PRIMARY KEY (`id`),
  KEY `IDX_723705D19395C3F3` (`customer_id`),
  KEY `IDX_723705D1F6054787` (`beneficiare_id`),
  KEY `IDX_723705D1F92F3E70` (`country_id`),
  KEY `IDX_723705D175E23604` (`town_id`),
  CONSTRAINT `FK_723705D175E23604` FOREIGN KEY (`town_id`) REFERENCES `town` (`id`),
  CONSTRAINT `FK_723705D19395C3F3` FOREIGN KEY (`customer_id`) REFERENCES `customer` (`id`),
  CONSTRAINT `FK_723705D1F6054787` FOREIGN KEY (`beneficiare_id`) REFERENCES `contactcustomer` (`id`),
  CONSTRAINT `FK_723705D1F92F3E70` FOREIGN KEY (`country_id`) REFERENCES `country` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `transaction_sandbox` (
  `id` int NOT NULL AUTO_INCREMENT,
  `customer_id` int DEFAULT NULL,
  `beneficiare_id` int DEFAULT NULL,
  `country_id` int DEFAULT NULL,
  `town_id` int DEFAULT NULL,
  `montant` double DEFAULT NULL,
  `fraisenvoi` double DEFAULT NULL,
  `montanttotal` double DEFAULT NULL,
  `datetransaction` datetime DEFAULT NULL,
  `raisontransaction` longtext COLLATE utf8mb4_unicode_ci,
  `modetransfert` varchar(255) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `branche` varchar(255) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `business_type` varchar(255) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `partenairenumero` varchar(255) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `typeservice` varchar(255) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `numerotransaction` varchar(255) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `numeroidentifiant` varchar(255) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `typetransaction` varchar(255) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `prestateurservice` varchar(255) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `operateur` varchar(255) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `status` varchar(255) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `fileimagename` varchar(255) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `isdelete` tinyint(1) DEFAULT NULL,
  `origin_fonds` varchar(255) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `relaction` varchar(255) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `partner_id` int DEFAULT NULL,
  `bank_iban` varchar(255) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `bank_account_number` varchar(255) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `bank_swift` varchar(255) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `agent` varchar(255) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  PRIMARY KEY (`id`),
  KEY `IDX_9040CD859395C3F3` (`customer_id`),
  KEY `IDX_9040CD85F6054787` (`beneficiare_id`),
  KEY `IDX_9040CD85F92F3E70` (`country_id`),
  KEY `IDX_9040CD8575E23604` (`town_id`),
  CONSTRAINT `FK_9040CD8575E23604` FOREIGN KEY (`town_id`) REFERENCES `town` (`id`),
  CONSTRAINT `FK_9040CD859395C3F3` FOREIGN KEY (`customer_id`) REFERENCES `customer` (`id`),
  CONSTRAINT `FK_9040CD85F6054787` FOREIGN KEY (`beneficiare_id`) REFERENCES `contactcustomer` (`id`),
  CONSTRAINT `FK_9040CD85F92F3E70` FOREIGN KEY (`country_id`) REFERENCES `country` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `user` (
  `id` int NOT NULL AUTO_INCREMENT,
  `email` varchar(180) COLLATE utf8mb4_unicode_ci NOT NULL,
  `name` varchar(255) COLLATE utf8mb4_unicode_ci NOT NULL,
  `lastname` varchar(255) COLLATE utf8mb4_unicode_ci NOT NULL,
  `roles` json NOT NULL,
  `password` varchar(255) COLLATE utf8mb4_unicode_ci NOT NULL,
  `authy_id` varchar(255) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `country_code` varchar(255) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `phone` varchar(255) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `country` varchar(255) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `photo` varchar(255) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `fcm_token` varchar(255) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `pin_code` varchar(255) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `chat_id` varchar(255) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `UNIQ_8D93D649E7927C74` (`email`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `user_action_log` (
  `id` int NOT NULL AUTO_INCREMENT,
  `user_id` int NOT NULL,
  `action` varchar(255) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `details` longtext COLLATE utf8mb4_unicode_ci,
  `ip_address` varchar(55) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `created_at` datetime DEFAULT NULL COMMENT '(DC2Type:datetime_immutable)',
  PRIMARY KEY (`id`),
  KEY `IDX_15A23069A76ED395` (`user_id`),
  CONSTRAINT `FK_15A23069A76ED395` FOREIGN KEY (`user_id`) REFERENCES `user` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `wace_data` (
  `id` int NOT NULL AUTO_INCREMENT,
  `type` varchar(255) COLLATE utf8mb4_unicode_ci NOT NULL,
  `name` varchar(255) COLLATE utf8mb4_unicode_ci NOT NULL,
  `service` varchar(255) COLLATE utf8mb4_unicode_ci NOT NULL,
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `wace_payer_service` (
  `id` int NOT NULL AUTO_INCREMENT,
  `code_iso` varchar(255) COLLATE utf8mb4_unicode_ci NOT NULL,
  `code_iso3` varchar(255) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `country_prefix` varchar(255) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `service_code` varchar(255) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `service_name` varchar(255) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `currency_code` varchar(255) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `payer_code` varchar(255) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `web_token` (
  `id` int NOT NULL AUTO_INCREMENT,
  `token` varchar(64) COLLATE utf8mb4_unicode_ci NOT NULL,
  `amount` decimal(15,2) NOT NULL,
  `country_id` bigint NOT NULL,
  `wa_id` varchar(20) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `used` tinyint(1) NOT NULL,
  `status` varchar(10) COLLATE utf8mb4_unicode_ci NOT NULL,
  `expires_at` datetime NOT NULL COMMENT '(DC2Type:datetime_immutable)',
  `used_at` datetime DEFAULT NULL COMMENT '(DC2Type:datetime_immutable)',
  `created_at` datetime NOT NULL COMMENT '(DC2Type:datetime_immutable)',
  PRIMARY KEY (`id`),
  UNIQUE KEY `UNIQ_9224A5A55F37A13B` (`token`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `zone` (
  `id` int NOT NULL AUTO_INCREMENT,
  `libelle` varchar(255) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `code` varchar(255) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40103 SET TIME_ZONE=@OLD_TIME_ZONE */;

/*!40101 SET SQL_MODE=@OLD_SQL_MODE */;
/*!40014 SET FOREIGN_KEY_CHECKS=@OLD_FOREIGN_KEY_CHECKS */;
/*!40014 SET UNIQUE_CHECKS=@OLD_UNIQUE_CHECKS */;
/*!40101 SET CHARACTER_SET_CLIENT=@OLD_CHARACTER_SET_CLIENT */;
/*!40101 SET CHARACTER_SET_RESULTS=@OLD_CHARACTER_SET_RESULTS */;
/*!40101 SET COLLATION_CONNECTION=@OLD_COLLATION_CONNECTION */;
/*!40111 SET SQL_NOTES=@OLD_SQL_NOTES */;

