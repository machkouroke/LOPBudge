package com.lop.budget.data.local.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Carte de paiement enregistrée par l'utilisateur (epic « Cartes enregistrées »).
 *
 * Ce n'est **pas** un compte : plusieurs cartes peuvent pointer vers le même compte, et c'est
 * l'usage visé. À ne pas confondre avec `AccountType.CARD`, qui désigne un *compte* de type carte
 * prépayée.
 *
 * **Aucune donnée sensible n'est stockable, par construction** : il n'existe ici aucune colonne
 * pouvant accueillir un numéro complet, une date d'expiration ou un cryptogramme. L'invariant ne
 * repose pas sur la validation d'un formulaire, mais sur l'absence de l'emplacement.
 *
 * [accountId] est **nullable** pour une évolution future, et porte une clé étrangère
 * `ON DELETE SET NULL` : supprimer un compte ne supprime aucune carte, elle survit sans
 * rattachement. Le code ne doit jamais supposer ce champ renseigné, même si l'interface impose
 * aujourd'hui de choisir un compte à la création.
 */
@Entity(
    tableName = "payment_cards",
    foreignKeys = [
        ForeignKey(
            entity = AccountEntity::class,
            parentColumns = ["id"],
            childColumns = ["accountId"],
            onDelete = ForeignKey.SET_NULL,
        ),
    ],
    indices = [
        // Deux cartes ne peuvent pas partager le même couple réseau + quatre derniers chiffres :
        // l'appariement d'une notification deviendrait ambigu. La contrainte est portée par la
        // base, pas seulement par le code appelant.
        Index(value = ["network", "last4"], unique = true),
        // Exigé par la clé étrangère : sans lui, chaque suppression de compte impose un parcours
        // complet de la table.
        Index("accountId"),
        Index("position"),
    ],
)
data class PaymentCardEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** Libellé affiché, choisi par l'utilisateur : « Curve Card », « Revolut perso ». */
    val label: String,
    /** Réseau de la carte, énumération fermée côté domaine : VISA, MASTERCARD, AMEX, OTHER. */
    val network: String,
    /**
     * Quatre derniers chiffres, stockés en **texte** : un entier perdrait le zéro de tête de
     * `0123`. La contrainte de forme vit dans le modèle de domaine, SQLite ne sachant pas
     * l'exprimer ici.
     */
    val last4: String,
    /** Apparence choisie dans une palette fournie. Aucun fichier importé (P-3 de l'enabler). */
    val appearance: String,
    /** Compte sur lequel les dépenses de cette carte atterrissent par défaut. */
    val accountId: Long?,
    /** Rang dans la pile affichée. L'ordre de lecture suit cette colonne, jamais l'identifiant. */
    val position: Int = 0,
)
