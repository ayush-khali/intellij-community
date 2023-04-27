// COMPILER_ARGUMENTS: -Xplugin=$TEST_DIR/parcelize_fake_plugin.jar
// FILE: main.kt

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

@Parcelize
data class DataParcelable(
    val foo: Int,
    val bar: String?,
) : Parcelable

val dataParcelableWrite = DataParcelable::writeToParcel

// FILE: Parcelable.kt

package android.os

class Parcel

interface Parcelable {
    fun describeContents(): Int
    fun writeToParcel(dest: Parcel, flags: Int)
}
