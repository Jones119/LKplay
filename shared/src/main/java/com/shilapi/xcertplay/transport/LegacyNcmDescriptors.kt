package com.shilapi.xcertplay.transport

/*
 * 移植来源 / provenance (read-only reverse engineering artifact)
 * ---------------------------------------------------------------------------
 * EasyPlay v74 (Lynk build of DiPlay, version `0.2.7-lynk-api19-74`, the build
 * actually running on the car: pid 3780) — jadx output at
 * `_analysis/easyplay/src/sources/com/shilapi/xcertplay/transport/LegacyUsbConfiguration.java`:
 *
 *   `ncm(byte[], int)`        :574-843   -> [LegacyNcmDescriptors.ncm]
 *   `parse(byte[])`           :845-949   -> [LegacyNcmDescriptors.parse]
 *   `Endpoint` (data class)   :34-121
 *   `Interface` (data class)  :128-327   (`isUsbMux` :276, `isNcm` :280,
 *                                         `isAppleEthernet` :284, `getHasBulkPair` :288)
 *   `Configuration`           :331-445   (`getUsbMux` :393, `getHasNcm` :407)
 *   `NcmLayout`               :449-572
 *   `u8`/`u16` little-endian   :1072-1078
 *
 * Why this exists: on API 19 (Android 4.4) `android.hardware.usb.UsbConfiguration`
 * does not exist at all, so a userspace NCM bridge cannot use
 * [NcmFunctionDiscovery] (pure API 21+) and has to parse the raw descriptor bytes
 * returned by `UsbDeviceConnection.getRawDescriptors()` itself. See
 * `docs/_digest/58-用户态NCM桥(lwIP)路线评估与迁移清单.md` §5 row 1 ("API19 NCM 发现 …
 * 照抄级", citing `EP/transport/LegacyUsbConfiguration.java:574-839,1063`).
 *
 * This file deliberately imports **no `android.*`**: it is pure JVM, so the unit test
 * `shared/src/test/java/com/shilapi/xcertplay/transport/LegacyNcmDescriptorsTest.kt`
 * can drive it with synthetic descriptor arrays. The side-effecting parts of the
 * reference (`materialize` :997-1013 reflection construction of `UsbInterface`/
 * `UsbEndpoint`, `select` :1015-1061 `USBDEVFS_SETCONFIGURATION` ioctl, `activeId`
 * :1063-1070 control transfer) are intentionally **not** ported here.
 */

private const val DESCRIPTOR_TYPE_DEVICE = 0x01
private const val DESCRIPTOR_TYPE_CONFIGURATION = 0x02
private const val DESCRIPTOR_TYPE_INTERFACE = 0x04
private const val DESCRIPTOR_TYPE_ENDPOINT = 0x05
private const val DESCRIPTOR_TYPE_CS_INTERFACE = 0x24

private const val DEVICE_DESCRIPTOR_MINIMUM_BYTES = 18
private const val CONFIGURATION_DESCRIPTOR_MINIMUM_BYTES = 9
private const val INTERFACE_DESCRIPTOR_MINIMUM_BYTES = 9
private const val ENDPOINT_DESCRIPTOR_MINIMUM_BYTES = 7

private const val APPLE_VENDOR_ID = 0x5AC

private const val USB_CLASS_CDC_DATA = 0x0A
private const val NCM_DATA_ACTIVE_ALTERNATE = 1
private const val NCM_DATA_IDLE_ALTERNATE = 0

private const val CS_SUBTYPE_CDC_UNION = 6
private const val CS_SUBTYPE_ETHERNET_FUNCTIONAL = 15
private const val CS_SUBTYPE_NCM_FUNCTIONAL = 26
private const val CDC_UNION_MINIMUM_BYTES = 5
private const val ETHERNET_FUNCTIONAL_MINIMUM_BYTES = 13
private const val NCM_FUNCTIONAL_MINIMUM_BYTES = 6
private const val ETHERNET_FUNCTIONAL_MAC_STRING_INDEX = 3
private const val ETHERNET_FUNCTIONAL_MAX_SEGMENT_SIZE = 8
private const val NCM_FUNCTIONAL_CAPABILITIES = 5

private const val ENDPOINT_TRANSFER_TYPE_MASK = 0x03
private const val ENDPOINT_TRANSFER_TYPE_BULK = 0x02
private const val ENDPOINT_TRANSFER_TYPE_INTERRUPT = 0x03
private const val ENDPOINT_DIRECTION_MASK = 0x80

/**
 * One endpoint of a parsed interface — mirrors the reference `Endpoint` data class
 * (`LegacyUsbConfiguration.java:34-121`), which is built from the standard 7-byte endpoint
 * descriptor: address at +2, attributes at +3, little-endian max packet size at +4, interval at +6.
 */
internal data class LegacyNcmEndpoint(
    val address: Int,
    val attributes: Int,
    val maxPacketSize: Int,
    val interval: Int,
) {
    /** `bmAttributes & 3`: 2 = bulk, 3 = interrupt (USB 2.0 §9.6.6). */
    val transferType: Int get() = attributes and ENDPOINT_TRANSFER_TYPE_MASK

    /** `bEndpointAddress & 0x80`: 0x80 = IN (device to host). */
    val isIn: Boolean get() = (address and ENDPOINT_DIRECTION_MASK) != 0

    /** Reference's `(attributes & 3) == 2` (bulk) checks at :733 and :297. */
    val isBulk: Boolean get() = transferType == ENDPOINT_TRANSFER_TYPE_BULK

    /** Reference's `(attributes & 3) == 3 && (address & 128) != 0` check at :628. */
    val isInterrupt: Boolean get() = transferType == ENDPOINT_TRANSFER_TYPE_INTERRUPT
}

/**
 * One interface (one alternate setting) of a parsed configuration — mirrors the reference
 * `Interface` data class (`LegacyUsbConfiguration.java:128-327`).
 *
 * [classDescriptors] holds **only** the `CS_INTERFACE` (`bDescriptorType == 0x24`, reference
 * `kind == 36` at :912) descriptors that follow this interface, sliced exactly `bLength` bytes each;
 * `CS_ENDPOINT` (`0x25`) descriptors are never collected by the reference parser, and [LegacyNcmDescriptors.ncm]
 * only ever looks at CS_INTERFACE subtypes 6/15/26.
 *
 * Caveat inherited from the reference data class: `equals`/`hashCode` of this data class compare
 * [classDescriptors] **by list equality**, i.e. element `ByteArray`s by identity — do not use this
 * type as a map key with class descriptors attached.
 */
internal data class LegacyNcmInterface(
    val id: Int,
    val alternate: Int,
    val deviceClass: Int,
    val subclass: Int,
    val protocol: Int,
    val endpoints: List<LegacyNcmEndpoint>,
    val classDescriptors: List<ByteArray>,
) {
    /** Reference `isUsbMux` (:276): Apple's USBMUX function (0xff/0xfe/0x02). */
    val isUsbMux: Boolean get() = deviceClass == 0xFF && subclass == 0xFE && protocol == 0x02

    /**
     * Reference `isNcm` (:280): CDC-NCM control interface (0x02/0x0d).
     *
     * 参照实现语义存疑：the reference does **not** check `bInterfaceProtocol == 0x01` (NCM) here, only
     * class and subclass; this port keeps that laxity so the ported judgment calls stay identical.
     */
    val isCdcNcmControl: Boolean get() = deviceClass == 0x02 && subclass == 0x0D

    /** Reference `isAppleEthernet` (:284). Not used by [LegacyNcmDescriptors.ncm]; kept for the `find()` port. */
    val isAppleEthernet: Boolean get() = deviceClass == 0xFF && subclass == 0xFD && protocol == 0x01

    /**
     * Reference `getHasBulkPair` (:288): at least one bulk OUT endpoint **and** at least one bulk IN endpoint.
     *
     * 参照实现语义存疑（jadx 产物）：the decompiled body renders the second scan as a loop without a
     * `break` followed by an unconditional `z2 = false`, which taken literally would make this getter
     * always `false` — and then `Configuration.getUsbMux` (:393) could never find anything, making the
     * whole NCM path unreachable. The only reading consistent with the rest of the class (and with the
     * first, correctly rendered `any { }` scan) is `any { bulk out } && any { bulk in }`, which is what
     * this port implements. The same jadx artifact appears in `Configuration.getHasNcm` (:407).
     */
    val hasBulkPair: Boolean
        get() = endpoints.any { it.isBulk && !it.isIn } && endpoints.any { it.isBulk && it.isIn }
}

/**
 * One USB configuration from the raw descriptor blob — mirrors the reference `Configuration`
 * (`LegacyUsbConfiguration.java:331-445`).
 *
 * The reference also exposes `usbMux`/`hasNcm` as computed getters over `interfaces`, so this port
 * keeps them computed (no duplicated constructor state).
 */
internal data class LegacyNcmConfiguration(
    val id: Int,
    val interfaces: List<LegacyNcmInterface>,
) {
    /** Reference `getUsbMux` (:393): the USBMUX function, but only if it carries a bulk pair. */
    val usbMux: LegacyNcmInterface?
        get() = interfaces.firstOrNull { it.isUsbMux && it.hasBulkPair }

    /** Convenience for the reference's `configuration.usbMux != null` guard in `ncm` (:595). */
    val hasUsbMux: Boolean get() = usbMux != null

    /** Reference `getHasNcm` (:407). Not used by [LegacyNcmDescriptors.ncm]; kept for the `find()` port. */
    val hasNcm: Boolean
        get() = interfaces.any { it.isCdcNcmControl } &&
            interfaces.any { it.deviceClass == USB_CLASS_CDC_DATA && it.hasBulkPair }
}

/**
 * The NCM layout [LegacyNcmDescriptors.ncm] resolves — mirrors the reference `NcmLayout`
 * (`LegacyUsbConfiguration.java:449-572`).
 *
 * @param control the CDC-NCM control interface (class 0x02 / subclass 0x0d, alternate 0).
 * @param data the data interface alternate setting that carries the two bulk endpoints (normally
 *   alternate 1).
 * @param idle the data interface's alternate 0 (no bulk endpoints); must exist.
 * @param iMacAddressIndex the **string descriptor index** of the MAC address — from CDC Ethernet
 *   functional descriptor byte +3. It is an index, *not* the MAC itself (the MAC string is read with
 *   a `GET_DESCRIPTOR`/`wValue = 0x0300 | index` control transfer, which is out of scope here).
 * @param maxSegmentSize CDC Ethernet functional descriptor bytes +8..+9 (`wMaxSegmentSize`),
 *   little-endian, raw field.
 * @param capabilities CDC-NCM functional descriptor byte +5 (`bmNetworkCapabilities`), raw field.
 */
internal data class LegacyNcmLayout(
    val control: LegacyNcmInterface,
    val data: LegacyNcmInterface,
    val idle: LegacyNcmInterface,
    val iMacAddressIndex: Int,
    val maxSegmentSize: Int,
    val capabilities: Int,
)

/**
 * Pure JVM port of EasyPlay v74's legacy (API 19) NCM descriptor discovery — the discovery logic
 * needed by the userspace NCM bridge of `docs/_digest/58-用户态NCM桥(lwIP)路线评估与迁移清单.md`
 * §5 row 1 ("API19 NCM 发现 … 照抄级").
 *
 * Provenance (read-only jadx output of EasyPlay v74 `0.2.7-lynk-api19-74`,
 * `_analysis/easyplay/src/sources/com/shilapi/xcertplay/transport/LegacyUsbConfiguration.java`):
 * `ncm(byte[], int)` :574-843, `parse(byte[])` :845-949, `Interface` :128-327,
 * `Configuration` :331-445, `NcmLayout` :449-572, `u8`/`u16` :1072-1078 — the file header lists the
 * per-member lines.
 *
 * Every validation, its **order**, and its message string is kept identical to the reference so that
 * a failure on the car reads the same in both implementations; the messages are asserted verbatim by
 * `LegacyNcmDescriptorsTest`. No `android.*` type is imported, so this object also runs under plain
 * JVM unit tests.
 */
internal object LegacyNcmDescriptors {
    /**
     * Reference `parse(byte[])` (:845-949) — walk the raw descriptor blob
     * (`UsbDeviceConnection.getRawDescriptors()` layout: device descriptor, then configuration
     * descriptor with its interfaces/endpoints/class-specific descriptors) and return every
     * well-formed configuration.
     *
     * Faithful quirks kept on purpose:
     *  - a malformed *configuration* descriptor `break`s the whole scan (later configurations are
     *    dropped) instead of being skipped;
     *  - a configuration is only reported when its interface block ends exactly on `wTotalLength`
     *    (`valid && part == end`, :940);
     *  - `CS_INTERFACE` (0x24) descriptors are attached to the *last* interface seen, and are dropped
     *    when no interface was seen yet; a stray endpoint descriptor with no preceding interface is
     *    ignored the same way;
     *  - the configuration id is byte +5 of the configuration descriptor (`bConfigurationValue`).
     */
    fun parse(raw: ByteArray): List<LegacyNcmConfiguration> {
        val result = ArrayList<LegacyNcmConfiguration>()
        var cursor = 0
        while (cursor + 2 <= raw.size) {
            val size = u8(raw, cursor)
            val type = u8(raw, cursor + 1)
            if (size < 2 || cursor + size > raw.size) break
            if (type != DESCRIPTOR_TYPE_CONFIGURATION) {
                cursor += size
                continue
            }
            // Order matters: the reference tests `size < 9` before reading wTotalLength, so a short
            // configuration descriptor never causes an out-of-bounds read.
            if (size < CONFIGURATION_DESCRIPTOR_MINIMUM_BYTES) break
            val total = u16(raw, cursor + 2)
            if (total < size || cursor + total > raw.size) break
            val end = cursor + total
            val interfaces = ArrayList<LegacyNcmInterface>()
            var part = cursor + size
            var valid = true
            while (part + 2 <= end) {
                val length = u8(raw, part)
                val kind = u8(raw, part + 1)
                if (length < 2 || part + length > end) {
                    valid = false
                    break
                }
                when {
                    kind == DESCRIPTOR_TYPE_INTERFACE -> {
                        if (length < INTERFACE_DESCRIPTOR_MINIMUM_BYTES) {
                            valid = false
                            break
                        }
                        interfaces.add(
                            LegacyNcmInterface(
                                id = u8(raw, part + 2),
                                alternate = u8(raw, part + 3),
                                deviceClass = u8(raw, part + 5),
                                subclass = u8(raw, part + 6),
                                protocol = u8(raw, part + 7),
                                endpoints = emptyList(),
                                classDescriptors = emptyList(),
                            ),
                        )
                    }

                    kind == DESCRIPTOR_TYPE_ENDPOINT && interfaces.isNotEmpty() -> {
                        if (length < ENDPOINT_DESCRIPTOR_MINIMUM_BYTES) {
                            valid = false
                            break
                        }
                        val index = interfaces.lastIndex
                        val current = interfaces[index]
                        interfaces[index] = current.copy(
                            endpoints = current.endpoints + LegacyNcmEndpoint(
                                address = u8(raw, part + 2),
                                attributes = u8(raw, part + 3),
                                maxPacketSize = u16(raw, part + 4),
                                interval = u8(raw, part + 6),
                            ),
                        )
                    }

                    kind == DESCRIPTOR_TYPE_CS_INTERFACE && interfaces.isNotEmpty() -> {
                        val index = interfaces.lastIndex
                        val current = interfaces[index]
                        interfaces[index] = current.copy(
                            classDescriptors = current.classDescriptors +
                                raw.copyOfRange(part, part + length),
                        )
                    }
                }
                part += length
            }
            if (valid && part == end) {
                result.add(LegacyNcmConfiguration(u8(raw, cursor + 5), interfaces))
            }
            cursor = end
        }
        return result
    }

    /**
     * Reference `ncm(byte[], int)` (:574-843) — resolve the NCM control/data/idle layout of the active
     * iPhone configuration out of the raw descriptor blob.
     *
     * Throws [IphoneUsbException.Protocol] with the reference's exact message:
     *  1. `Legacy NCM requires an Apple USB device descriptor` — `raw.size < 18`, `bLength != 18`,
     *     `bDescriptorType != 1`, or `idVendor != 0x5AC` at offset 8 (little-endian). Only the vendor
     *     id is checked, never the product id (:579).
     *  2. `Active iPhone USB configuration has no unique USBMUX function` — no *single* configuration
     *     whose id equals [configurationId] **and** that exposes a USBMUX interface with a bulk pair
     *     (:584-607). Note the coupling: `usbMux` itself requires `hasBulkPair` (:393), so a
     *     configuration whose 0xff/0xfe/0x02 interface has no bulk pair is not selectable.
     *  3. `Active iPhone USB configuration has no unique NCM control interface` — the control
     *     candidates are `isNcm && alternate == 0`; among them the ones with an interrupt **IN**
     *     endpoint ("notifying") win: exactly one -> it; none -> the candidate list must itself be a
     *     single element; more than one -> fail (:608-644).
     *  4. `NCM control interface has no unique CDC Union descriptor` — exactly one `CS_INTERFACE`
     *     subtype 6 with `size >= 5` (:648-671).
     *  5. `NCM union does not name its control interface` — `union[3] == control.id` (:672-674).
     *  6. `NCM data interface is missing or ambiguous` — the data candidates are
     *     `class == 0x0a && hasBulkPair && alternate != 0 && id in union[4..]`, and their interface
     *     **ids** must collapse to exactly one (:682-699).
     *  7. `NCM data alternate setting is ambiguous` — prefer the single `alternate == 1`, else the
     *     candidate list must itself be a single element (:700-727).
     *  8. `NCM data interface must expose exactly two bulk endpoints` — exactly two endpoints with
     *     `bmAttributes & 3 == 2` **and** both `wMaxPacketSize > 0` (:729-842).
     *  9. `NCM data interface has no idle alternate setting` — a single interface with the data id and
     *     `alternate == 0` must exist (:756-783).
     * 10. `NCM Ethernet functional descriptor is missing` — a single `CS_INTERFACE` subtype 15 with
     *     `size >= 13` on the control interface (:784-809).
     * 11. `NCM functional descriptor is missing` — a single `CS_INTERFACE` subtype 26 with
     *     `size >= 6` on the control interface (:810-839).
     *
     * 参照实现语义存疑：the union's slave list is `union.drop(4)` over the **whole** descriptor array
     * (so it honours `bLength > 5`, i.e. multiple slaves) and an empty slave set is impossible because
     * `size >= 5` guarantees byte 4 exists; but a union naming a *non-data* interface id is accepted
     * silently as long as one real data candidate also matches. This port keeps that behaviour rather
     * than tightening it, because every added check could reject a device the reference accepted.
     */
    @Throws(IphoneUsbException.Protocol::class)
    fun ncm(raw: ByteArray, configurationId: Int): LegacyNcmLayout {
        if (raw.size < DEVICE_DESCRIPTOR_MINIMUM_BYTES ||
            u8(raw, 0) != 18 ||
            u8(raw, 1) != DESCRIPTOR_TYPE_DEVICE ||
            u16(raw, 8) != APPLE_VENDOR_ID
        ) {
            throw IphoneUsbException.Protocol("Legacy NCM requires an Apple USB device descriptor")
        }

        val configuration = parse(raw).singleOrNull { it.id == configurationId && it.hasUsbMux }
            ?: throw IphoneUsbException.Protocol(
                "Active iPhone USB configuration has no unique USBMUX function",
            )

        val controls = configuration.interfaces.filter {
            it.isCdcNcmControl && it.alternate == NCM_DATA_IDLE_ALTERNATE
        }
        val notifying = controls.filter { candidate -> candidate.endpoints.any { it.isInterrupt && it.isIn } }
        val control = when {
            notifying.size == 1 -> notifying.single()
            notifying.isEmpty() -> controls.singleOrNull()
            else -> null
        } ?: throw IphoneUsbException.Protocol(
            "Active iPhone USB configuration has no unique NCM control interface",
        )

        val union = control.classDescriptors.singleOrNull {
            it.size >= CDC_UNION_MINIMUM_BYTES && u8(it, 2) == CS_SUBTYPE_CDC_UNION
        } ?: throw IphoneUsbException.Protocol("NCM control interface has no unique CDC Union descriptor")

        if (u8(union, 3) != control.id) {
            throw IphoneUsbException.Protocol("NCM union does not name its control interface")
        }
        val allowedData = union.drop(4).map { it.toInt() and 0xFF }.toSet()

        val candidates = configuration.interfaces.filter {
            it.deviceClass == USB_CLASS_CDC_DATA &&
                it.hasBulkPair &&
                it.alternate != NCM_DATA_IDLE_ALTERNATE &&
                allowedData.contains(it.id)
        }
        if (candidates.map { it.id }.distinct().size != 1) {
            throw IphoneUsbException.Protocol("NCM data interface is missing or ambiguous")
        }

        val data = candidates.singleOrNull { it.alternate == NCM_DATA_ACTIVE_ALTERNATE }
            ?: candidates.singleOrNull()
            ?: throw IphoneUsbException.Protocol("NCM data alternate setting is ambiguous")

        val bulk = data.endpoints.filter { it.isBulk }
        if (bulk.size != 2 || bulk.any { it.maxPacketSize <= 0 }) {
            throw IphoneUsbException.Protocol(
                "NCM data interface must expose exactly two bulk endpoints",
            )
        }

        val idle = configuration.interfaces.singleOrNull {
            it.id == data.id && it.alternate == NCM_DATA_IDLE_ALTERNATE
        } ?: throw IphoneUsbException.Protocol("NCM data interface has no idle alternate setting")

        val ethernet = control.classDescriptors.singleOrNull {
            it.size >= ETHERNET_FUNCTIONAL_MINIMUM_BYTES && u8(it, 2) == CS_SUBTYPE_ETHERNET_FUNCTIONAL
        } ?: throw IphoneUsbException.Protocol("NCM Ethernet functional descriptor is missing")

        val ncm = control.classDescriptors.singleOrNull {
            it.size >= NCM_FUNCTIONAL_MINIMUM_BYTES && u8(it, 2) == CS_SUBTYPE_NCM_FUNCTIONAL
        } ?: throw IphoneUsbException.Protocol("NCM functional descriptor is missing")

        return LegacyNcmLayout(
            control = control,
            data = data,
            idle = idle,
            iMacAddressIndex = u8(ethernet, ETHERNET_FUNCTIONAL_MAC_STRING_INDEX),
            maxSegmentSize = u16(ethernet, ETHERNET_FUNCTIONAL_MAX_SEGMENT_SIZE),
            capabilities = u8(ncm, NCM_FUNCTIONAL_CAPABILITIES),
        )
    }

    /** Reference `u8(byte[], int)` (:1072-1074). */
    private fun u8(raw: ByteArray, offset: Int): Int = raw[offset].toInt() and 0xFF

    /** Reference `u16(byte[], int)` (:1076-1078): little-endian. */
    private fun u16(raw: ByteArray, offset: Int): Int = u8(raw, offset) or (u8(raw, offset + 1) shl 8)
}
