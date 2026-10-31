package it.bilar.sentinel

import org.json.JSONObject

internal object ScooterShareFile {
    fun encode(profile: YouFsProfile, name: String, address: String?): ByteArray {
        val data = JSONObject(profile.json.toString()).put("name",name)
        address?.let { data.put("address",it) }
        return decode(data.toString().toByteArray(Charsets.UTF_8))
    }
    fun decode(bytes: ByteArray): ByteArray {
        require(!bytes.take(5).toByteArray().contentEquals(byteArrayOf(83,78,84,76,1))) {
            appText(R.string.this_file_uses_the_old_protection_format_export_the_scooter_again_from)
        }
        return YouFsProfile.parse(bytes).json.toString().toByteArray(Charsets.UTF_8)
    }
}
