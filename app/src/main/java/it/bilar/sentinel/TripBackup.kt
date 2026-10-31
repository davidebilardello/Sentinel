package it.bilar.sentinel

import android.util.Xml
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.time.Instant

internal object TripBackup {
    fun encode(store: TripStore): ByteArray {
        val records = store.syncRecords().filter { !it.deleted }
        require(records.isNotEmpty()) { appText(R.string.there_are_no_completed_trips_to_export) }
        val json = JSONObject().put("format","sentinel-trip-backup").put("version",1)
            .put("archive",JSONObject(SyncRecord.encode(records).toString(Charsets.UTF_8)))
        val clear = json.toString().toByteArray()
        return try { PortableArchive.compress(clear) } finally { clear.fill(0) }
    }

    fun decode(bytes: ByteArray): List<SyncRecord> {
        val clear = PortableArchive.unpack(bytes)
        return try {
            val json = JSONObject(clear.toString(Charsets.UTF_8))
            require(json.getString("format") == "sentinel-trip-backup" && json.getInt("version") == 1) { appText(R.string.incompatible_backup) }
            SyncRecord.decode(json.getJSONObject("archive").toString().toByteArray()).also { records ->
                require(records.isNotEmpty() && records.all { it.kind == "trip" && !it.deleted }) { appText(R.string.invalid_trip_backup) }
            }
        } finally { clear.fill(0) }
    }

    fun gpx(store: TripStore): ByteArray {
        val records = store.syncRecords().filter { !it.deleted }
        require(records.isNotEmpty()) { appText(R.string.there_are_no_completed_trips_to_export) }
        val output = ByteArrayOutputStream()
        val xml = Xml.newSerializer().apply { setOutput(output,"UTF-8"); startDocument("UTF-8",true) }
        xml.startTag(null,"gpx").attribute(null,"xmlns","http://www.topografix.com/GPX/1/1")
            .attribute(null,"version","1.1").attribute(null,"creator","Sentinel")
        for(record in records) {
            val data = record.data; val points = data.getJSONArray("points")
            xml.startTag(null,"trk").startTag(null,"name")
                .text((data.optJSONObject("scooter")?.getString("name") ?: "Sentinel")+" · "+Instant.ofEpochMilli(data.getLong("started")))
                .endTag(null,"name")
            var segment: Int? = null
            for(i in 0 until points.length()) {
                val point = points.getJSONArray(i)
                if(segment != point.getInt(4)) {
                    if(segment != null) xml.endTag(null,"trkseg")
                    xml.startTag(null,"trkseg"); segment = point.getInt(4)
                }
                xml.startTag(null,"trkpt").attribute(null,"lat",point.getDouble(0).toString()).attribute(null,"lon",point.getDouble(1).toString())
                    .startTag(null,"time").text(Instant.ofEpochMilli(point.getLong(2)).toString()).endTag(null,"time").endTag(null,"trkpt")
            }
            if(segment != null) xml.endTag(null,"trkseg")
            xml.endTag(null,"trk")
        }
        xml.endTag(null,"gpx"); xml.endDocument()
        require(output.size() <= PortableArchive.MAX_CLEAR) { appText(R.string.archive_too_large_2) }
        return output.toByteArray()
    }
}
