package com.quentin.navigationapp.ui.fragments.home

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import com.quentin.navigationapp.R
import com.quentin.navigationapp.model.Profile
import com.quentin.navigationapp.model.VehicleSubType
import org.json.JSONArray
import org.json.JSONObject

class HomeFragment : Fragment() {

    // --- Clés SharedPreferences ---
    private val PREFS_NAME = "app_navigation_prefs"
    private val KEY_PROFILES_JSON = "profiles_json"
    private val KEY_CURRENT_PROFILE = "current_profile"

    private lateinit var spinnerProfiles: Spinner
    private lateinit var adapterProfiles: ArrayAdapter<String>
    private var profilesList: MutableList<Profile> = mutableListOf()

    private val FOOTER_ADD = "➕ Ajouter un profil…"
    private var lastSelectedIndex = 0
    private lateinit var ivVehicleIcon: ImageView

    private val prefs by lazy {
        requireContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.home_page, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        spinnerProfiles = view.findViewById(R.id.spinner_profiles)
        ivVehicleIcon = view.findViewById(R.id.iv_vehicle_icon)

        // 1) Charger tous les profils depuis SharedPreferences (JSON → List<Profile>)
        profilesList = loadProfiles()

        // 2) Construire la liste de noms à afficher (Profile.name + FOOTER_ADD)
        val displayNames = profilesList.map { it.name }.toMutableList()
        displayNames.add(FOOTER_ADD)

        adapterProfiles = ArrayAdapter(
            requireContext(),
            R.layout.spinner_selected_item,
            displayNames
        ).also { adp ->
            adp.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }
        spinnerProfiles.adapter = adapterProfiles

        // 3) Positionner la sélection sur le profil actif (s’il existe)
        val currentName = loadCurrentProfileName()
        if (!currentName.isNullOrBlank()) {
            val idx = profilesList.indexOfFirst { it.name == currentName }
            if (idx >= 0) {
                spinnerProfiles.setSelection(idx)
                lastSelectedIndex = idx
                updateVehicleIcon(profilesList[idx])
            }
        } else {
            // S’il n’y a pas encore de profil actif
            if (profilesList.isNotEmpty()) {
                spinnerProfiles.setSelection(0)
                lastSelectedIndex = 0
                saveCurrentProfileName(profilesList[0].name)
                updateVehicleIcon(profilesList[0])
            } else {
                // Aucune entrée → on force sur “➕ Ajouter…”
                spinnerProfiles.setSelection(displayNames.size - 1)
                lastSelectedIndex = displayNames.size - 1
                ivVehicleIcon.setImageResource(R.mipmap.ic_profile_default_image_foreground)
            }
        }


        // 4) Gérer la sélection dans le Spinner
        spinnerProfiles.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(
                parent: AdapterView<*>,
                itemView: View?,
                position: Int,
                id: Long
            ) {
                val chosen = adapterProfiles.getItem(position) ?: return
                if (chosen == FOOTER_ADD) {
                    // L’utilisateur veut ajouter un profil → revenir en arrière dans le Spinner
                    spinnerProfiles.setSelection(lastSelectedIndex)
                    showAddProfileDialog()
                } else {
                    // L’utilisateur a sélectionné un profil existant
                    lastSelectedIndex = position
                    saveCurrentProfileName(chosen)
                    // Récupérer l’objet Profile correspondant et mettre à jour l’icône
                    val profile = profilesList.firstOrNull { it.name == chosen }
                    profile?.let { updateVehicleIcon(it) }
                }
            }
            override fun onNothingSelected(parent: AdapterView<*>) { }
        }
    }

    /** Lit la liste de profils (clé JSON) et la renvoie en MutableList<Profile>. */
    private fun loadProfiles(): MutableList<Profile> {
        val jsonString = prefs.getString(KEY_PROFILES_JSON, null) ?: return mutableListOf()
        val jsonArray = JSONArray(jsonString)
        val list = mutableListOf<Profile>()
        for (i in 0 until jsonArray.length()) {
            val obj = jsonArray.getJSONObject(i)

            // Récupérer le sous-type complet (objet JSON)
            val subTypeObj = obj.getJSONObject("subType")
            val subType = VehicleSubType(
                label = subTypeObj.getString("label"),
                routingType = subTypeObj.getString("routingType")
            )

            val p = Profile(
                name = obj.getString("name"),
                type = obj.getString("type"),
                subType = subType,
                consumption = obj.getDouble("consumption")
            )
            list.add(p)
        }
        return list
    }

    /** Sauvegarde la liste complète de Profile en JSON. */
    private fun saveProfiles(profiles: List<Profile>) {
        val jsonArray = JSONArray()

        for (profile in profiles) {
            val subTypeJson = JSONObject().apply {
                put("label", profile.subType.label)
                put("routingType", profile.subType.routingType)
            }

            val obj = JSONObject().apply {
                put("name", profile.name)
                put("type", profile.type)
                put("subType", subTypeJson)
                put("consumption", profile.consumption)
            }
            jsonArray.put(obj)
        }

        prefs.edit().putString(KEY_PROFILES_JSON, jsonArray.toString()).apply()
    }

    /** Renvoie le nom du profil actif ou null si aucun. */
    private fun loadCurrentProfileName(): String? {
        return prefs.getString(KEY_CURRENT_PROFILE, null)
    }

    /** Sauvegarde simplement le nom du profil actif. */
    private fun saveCurrentProfileName(name: String) {
        prefs.edit()
            .putString(KEY_CURRENT_PROFILE, name)
            .apply()
    }

    /**
     * updateVehicleIcon
     * Met à jour ivVehicleIcon en fonction du type (ou du sous-type “Scooter”).
     * @param profile : Profile
     * @calls displayProfileInfo
     */
    private fun updateVehicleIcon(profile: Profile) {

        if (profile.imageUri == null) {
            val resId = when {
                profile.type.equals("Moto", ignoreCase = true) -> R.mipmap.ic_profile_default_image_foreground
                profile.type.equals("Moto 50cc", ignoreCase = true) -> R.mipmap.scooter
                profile.type.equals("Quad", ignoreCase = true) -> R.mipmap.quad
                profile.type.equals("Voiture", ignoreCase = true) -> R.mipmap.ic_profile_default_image_foreground
                profile.type.equals("Trottinette", ignoreCase = true) -> R.mipmap.ic_profile_default_image_foreground

                else -> R.mipmap.ic_profile_default_image_foreground
            }

            ivVehicleIcon.setImageResource(resId)
        }else {
            ivVehicleIcon.setImageURI(profile.imageUri)
        }

        displayProfileInfo(profile)
    }

    /**
     * displayProfileInfo
     * @param profile : Profile
     */
    private fun displayProfileInfo(profile: Profile) {

        val gridLayout = view?.findViewById<GridLayout>(R.id.grid_profile_info)

        if (gridLayout != null) {
            gridLayout.removeAllViews()
            val profileData = listOf(
                "type" to profile.subType,
                "Consommation" to profile.consumption.toString() + " L/100km"
            )

            for ((title, value) in profileData) {
                val card = LayoutInflater.from(requireContext())
                    .inflate(R.layout.item_profile_info, gridLayout, false)

                card.findViewById<TextView>(R.id.tv_title).text = title
                card.findViewById<TextView>(R.id.tv_value).text = value.toString()
                gridLayout.addView(card)
            }
        }
    }

    // ─── Boîte de dialogue pour ajouter un nouveau profil (avec 5 champs) ─────────────
    private fun showAddProfileDialog() {
        // Inflater le layout personnalisé
        val dialogView = LayoutInflater.from(requireContext())
            .inflate(R.layout.dialog_add_profile, null)

        // Récupérer toutes les vues
        val spinnerType = dialogView.findViewById<Spinner>(R.id.spinner_type)
        val spinnerSubType = dialogView.findViewById<Spinner>(R.id.spinner_subtype)
        val etName = dialogView.findViewById<EditText>(R.id.et_profile_name)
        val etConsumption = dialogView.findViewById<EditText>(R.id.et_consumption)

        // Définir la liste des types
        val types = listOf("Moto", "Moto 50cc", "Quad", "Voiture", "Vélo", "Trottinette")
        val adapterType = ArrayAdapter(
            requireContext(),
            android.R.layout.simple_spinner_item,
            types
        ).also { it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
        spinnerType.adapter = adapterType

        // Map des sous-types selon le type
        val subTypesMap = mapOf(
            "Moto" to listOf(
                VehicleSubType("Roadster", "car"),
                VehicleSubType("Sportive", "car"),
                VehicleSubType("Custom", "car"),
                VehicleSubType("Trail", "car"),
                VehicleSubType("Enduro", "car"),
                VehicleSubType("Supermotard", "car"),
                VehicleSubType("Touring", "car"),
                VehicleSubType("Cross", "bike"),
                VehicleSubType("Café Racer", "car")
            ),
            "Moto 50cc" to listOf(
                VehicleSubType("Scooter", "bike"),
                VehicleSubType("Supermotard", "bike"),
                VehicleSubType("Dirt", "mtb"),
                VehicleSubType("Mobylette", "bike")
            ),
            "Quad" to listOf(
                VehicleSubType("Route", "car"),
                VehicleSubType("Tout-terrain", "mtb")
            ),
            "Voiture" to listOf(
                VehicleSubType("Citadine", "car"),
                VehicleSubType("Berline", "car"),
                VehicleSubType("Break", "car"),
                VehicleSubType("SUV", "car"),
                VehicleSubType("4x4", "car"),
                VehicleSubType("Coupé", "car"),
                VehicleSubType("Cabriolet", "car"),
                VehicleSubType("Monospace", "car"),
                VehicleSubType("Utilitaire", "car")
            ),
            "Vélo" to listOf(
                VehicleSubType("VTT", "bike"),
                VehicleSubType("VTC", "bike"),
                VehicleSubType("route", "bike")
            ),
            "Trottinette" to listOf(
                VehicleSubType("Électrique", "bike"),
                VehicleSubType("Tout-terrain", "bike")
            )
        )

        // Mettre à jour spinnerSubType dès qu’un type est sélectionné
        spinnerType.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(
                parent: AdapterView<*>,
                view: View?,
                position: Int,
                id: Long
            ) {
                val selType = types[position]
                val listSub = subTypesMap[selType] ?: emptyList()
                val adapterSub = ArrayAdapter(
                    requireContext(),
                    android.R.layout.simple_spinner_item,
                    listSub
                ).also { it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
                spinnerSubType.adapter = adapterSub
            }
            override fun onNothingSelected(parent: AdapterView<*>) {}
        }

        // Prendre le premier type par défaut
        spinnerType.setSelection(0) // déclenche le listener ci-dessus

        AlertDialog.Builder(requireContext())
            .setTitle("Nouveau profil Véhicule")
            .setView(dialogView)
            .setPositiveButton("Enregistrer") { dlg, _ ->
                // Lire tous les champs
                val chosenType = spinnerType.selectedItem as String
                val chosenSub = spinnerSubType.selectedItem as VehicleSubType
                val nameInput = etName.text.toString().trim()
                val consumptionInput = etConsumption.text.toString().trim()

                // Validation minimale
                if (nameInput.isEmpty() || consumptionInput.isEmpty()) {
                    Toast.makeText(requireContext(),
                        "Tous les champs sont obligatoires", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }

                // Conversion consommation en Double
                val consValue = consumptionInput.toDoubleOrNull()
                if (consValue == null) {
                    Toast.makeText(requireContext(),
                        "Consommation invalide (doit être un nombre)", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }

                // Vérifier nom unique (insensible à la casse)
                if (profilesList.any { it.name.equals(nameInput, ignoreCase = true) }) {
                    Toast.makeText(requireContext(),
                        "Le profil \"$nameInput\" existe déjà", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }

                // Créer le nouvel objet Profile
                val newProfile = Profile(
                    name = nameInput,
                    type = chosenType,
                    subType = chosenSub,
                    consumption = consValue
                )

                // Ajouter, trier, sauvegarder
                profilesList.add(newProfile)
                profilesList.sortBy { it.name.lowercase() }
                saveProfiles(profilesList)

                // Mettre à jour la liste de noms pour le Spinner
                val newDisplay = profilesList.map { it.name }.toMutableList()
                newDisplay.add(FOOTER_ADD)
                adapterProfiles.clear()
                adapterProfiles.addAll(newDisplay)
                adapterProfiles.notifyDataSetChanged()

                // Sélectionner et enregistrer immédiatement ce profil
                val newIdx = profilesList.indexOfFirst { it.name == nameInput }
                spinnerProfiles.setSelection(newIdx)
                lastSelectedIndex = newIdx
                saveCurrentProfileName(nameInput)

                // Afficher l’icône correspondante
                updateVehicleIcon(newProfile)

                Toast.makeText(requireContext(),
                    "Profil \"$nameInput\" ajouté", Toast.LENGTH_SHORT).show()

                dlg.dismiss()
            }
            .setNegativeButton("Annuler") { dlg, _ ->
                dlg.cancel()
            }
            .show()
    }
}
