package it.bilar.sentinel

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun YouFsInspectionPanel(inspection: YouFsInspection?) {
    Text(appText(R.string.youfs_a_inspection), fontSize = 23.sp)
    if (inspection == null) {
        Text(appText(R.string.choose_youfs_a_in_settings_add_the_device_and_connect_close_the_origin), color = Color(0xFF89939D), fontSize = 12.sp, modifier = Modifier.padding(top = 10.dp))
    } else {
        Text(appText(R.string.service_inventory_only_command_support_is_not_confirmed), color = Color(0xFFFFC970), fontSize = 11.sp, modifier = Modifier.padding(vertical = 10.dp))
        Column(Modifier.fillMaxWidth().heightIn(max = 350.dp).background(Color(0xFF151A1F), RoundedCornerShape(16.dp)).verticalScroll(rememberScrollState()).padding(14.dp)) {
            Text(inspection.text(), color = Color(0xFF89939D), fontFamily = FontFamily.Monospace, fontSize = 10.sp)
        }
    }
}
