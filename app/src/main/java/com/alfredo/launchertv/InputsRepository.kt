package com.alfredo.launchertv

import android.content.Context
import android.content.Intent
import android.media.tv.TvContract
import android.media.tv.TvInputInfo
import android.media.tv.TvInputManager

/** Entradas HDMI con algo conectado. */
object InputsRepository {

    fun manager(context: Context): TvInputManager? =
        context.getSystemService(Context.TV_INPUT_SERVICE) as? TvInputManager

    fun loadConnected(context: Context): List<Tile.Input> {
        val tim = manager(context) ?: return emptyList()
        val inputs: List<TvInputInfo> = try { tim.tvInputList } catch (e: Exception) { return emptyList() }
        val out = ArrayList<Tile.Input>()
        for (info in inputs) {
            if (info.type != TvInputInfo.TYPE_HDMI) continue
            val state = try { tim.getInputState(info.id) } catch (e: Exception) { TvInputManager.INPUT_STATE_DISCONNECTED }
            if (state == TvInputManager.INPUT_STATE_DISCONNECTED) continue

            val custom = try { info.loadCustomLabel(context)?.toString() } catch (e: Exception) { null }
            val label = try { info.loadLabel(context)?.toString() } catch (e: Exception) { null }
            val name = listOf(custom, label).firstOrNull { !it.isNullOrBlank() }?.trim()
                ?: context.getString(R.string.hdmi_generic, out.size + 1)
            out.add(Tile.Input(info.id, name, DeviceKind.detect(name)))
        }
        return out
    }

    fun launchIntent(input: Tile.Input): Intent {
        val uri = TvContract.buildChannelUriForPassthroughInput(input.inputId)
        return Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
}
