package com.sih.legmet.paddleocr_test

import android.util.Log

/**
 * Legal Metrology (Packaged Commodities) Rules 2011 Compliance Engine
 * Validates OCR-extracted text against mandatory declaration requirements
 * and font size rules using ArUco-based pixel-to-mm calibration.
 */
class ComplianceEngine(
    private val pixelToMmRatio: Double, // mm per pixel from ArUco calibration; 0.0 = no calibration
    private val minFontHeightMm: Double = 1.0 // Legal Metrology minimum 1mm
) {
    private val TAG = "ComplianceEngine"
    private val hasCalibration = pixelToMmRatio > 0
    private val minFontHeightPx = if (hasCalibration) (minFontHeightMm / pixelToMmRatio).toInt() else 0

    /**
     * Analyze OCR results and return compliance report
     */
    fun analyze(results: List<OCRResult>, imageWidth: Int, imageHeight: Int): ComplianceReport {
        Log.d(TAG, "Analyzing ${results.size} OCR results with pixel/mm ratio: $pixelToMmRatio (hasCalibration=$hasCalibration)")

        // Combine all text with positions
        val allText = results.joinToString(" ") { it.text }
        val allTextUpper = allText.uppercase()

        // Extract structured fields using regex patterns
        val extractedFields = extractFields(results)

        // Check mandatory declarations (ALWAYS runs - doesn't need calibration)
        val mandatoryChecks = checkMandatoryDeclarations(extractedFields, allTextUpper)

        // Check font size compliance (ONLY if calibrated)
        val fontSizeChecks = if (hasCalibration) checkFontSizes(results) else emptyList()

        // Calculate overall compliance
        val totalChecks = mandatoryChecks.size + fontSizeChecks.size
        val passedChecks = mandatoryChecks.count { it.passed } + fontSizeChecks.count { it.passed }
        val overallCompliant = mandatoryChecks.all { it.passed } && fontSizeChecks.all { it.passed }

        return ComplianceReport(
            overallCompliant = overallCompliant,
            totalChecks = totalChecks,
            passedChecks = passedChecks,
            mandatoryChecks = mandatoryChecks,
            fontSizeChecks = fontSizeChecks,
            extractedFields = extractedFields,
            minFontHeightPx = minFontHeightPx,
            pixelToMmRatio = pixelToMmRatio
        )
    }

    /**
     * Extract structured fields from OCR results using Legal Metrology patterns.
     * Line-based: each field's label and value are matched within a single OCR line,
     * which avoids the classic bug of greedy cross-line matches in space-joined text.
     */
    private fun extractFields(results: List<OCRResult>): ExtractedFields {
        // Sort lines in reading order: top to bottom, then left to right.
        // OCR boxes carry the actual image position, so this reflects layout, not OCR order.
        val sorted = results
            .filter { it.text.isNotBlank() }
            .sortedWith(compareBy({ minY(it) }, { minX(it) }))

        val allText = sorted.joinToString(" ") { it.text }
        val allUpper = allText.uppercase()

        var productName: String? = null
        var netQuantity: String? = null
        var mrp: String? = null
        var mfgDate: String? = null
        var bestBefore: String? = null
        var manufacturer: String? = null
        var address: String? = null
        var phone: String? = null
        var email: String? = null
        var customerCare: String? = null
        var countryOfOrigin: String? = null
        var batchNumber: String? = null

        // ---- 1. Product / generic name: most prominent (largest) text in the top half ----
        val fieldLabel = fieldLabelRegex()
        val keywordFree = sorted.filter { !fieldLabel.containsMatchIn(it.text) }
        val imageBottom = sorted.maxOfOrNull { maxY(it) } ?: 0f
        val upperHalf = keywordFree.filter { minY(it) < imageBottom * 0.6f }
        productName = upperHalf.maxByOrNull { boxHeight(it) }?.text?.trim()
            ?: keywordFree.firstOrNull()?.text?.trim()

        // ---- 2. Per-line label -> value matching (values stay inside their own line) ----
        for (line in sorted) {
            val t = line.text.trim()
            if (t.isEmpty()) continue

            val isDateLine = mfgDate != null || bestBefore != null
            if (netQuantity == null) {
                val v = captureValue(t, netQtyPattern())
                if (v != null) netQuantity = v
            }
            if (mrp == null) {
                val v = captureValue(t, mrpPattern())
                if (v != null) mrp = "Rs. $v"
            }
            if (mfgDate == null) {
                val v = captureValue(t, mfgDatePattern())
                if (v != null) mfgDate = cleanDate(v)
            }
            if (bestBefore == null) {
                val v = captureValue(t, bestBeforePattern())
                if (v != null) bestBefore = cleanDate(v)
            }
            if (manufacturer == null) {
                val v = captureValue(t, manufacturerPattern())
                if (v != null && !isDateLine && v.length >= 3 && !v.matches(Regex("(?i)date|on|in|by|for|of")))
                    manufacturer = v
            }
            if (batchNumber == null) {
                val v = captureValue(t, batchPattern())
                if (v != null) batchNumber = v
            }
            if (countryOfOrigin == null) {
                val v = captureValue(t, countryPattern())
                if (v != null) countryOfOrigin = v
            }
            if (customerCare == null) {
                val v = captureValue(t, carePattern())
                if (v != null) customerCare = v
            }
            if (phone == null) {
                val v = captureValue(t, phonePattern())
                if (v != null) phone = v
            }
        }

        // Simple full-text matches that are safe even across line wraps
        email = EMAIL_PATTERN.find(allText)?.value

        // ---- 3. Address: lines physically below the manufacturer line ----
        if (manufacturer != null) {
            val makerKey = manufacturer.uppercase().take(5)
            val makerIdx = sorted.indexOfFirst { it.text.uppercase().contains(makerKey) }
            if (makerIdx in sorted.indices) {
                val makerLine = sorted[makerIdx]
                val makerHeight = boxHeight(makerLine)
                val addressLines = ArrayList<String>()
                var prevBottom = maxY(makerLine)
                var i = makerIdx + 1
                while (i < sorted.size && addressLines.size < 3) {
                    val rt = sorted[i].text.trim()
                    if (rt.isEmpty() || isFieldLine(rt)) break
                    val gap = minY(sorted[i]) - prevBottom
                    // Separate block: vertical gap over ~1.6x the local line height
                    if (gap > 1.6f * maxOf(makerHeight, boxHeight(sorted[i]))) break
                    addressLines.add(rt)
                    prevBottom = maxY(sorted[i])
                    i++
                }
                if (addressLines.isNotEmpty()) address = addressLines.joinToString(" ")
            }
        }

        return ExtractedFields(
            productName = productName,
            netQuantity = netQuantity,
            mrp = mrp,
            mfgDate = mfgDate,
            bestBefore = bestBefore,
            manufacturer = manufacturer,
            address = address,
            phone = phone,
            email = email,
            customerCare = customerCare,
            countryOfOrigin = countryOfOrigin,
            batchNumber = batchNumber
        )
    }

    // ---- geometry helpers ----
    private fun minX(r: OCRResult): Float = r.box.minOf { it.x }
    private fun minY(r: OCRResult): Float = r.box.minOf { it.y }
    private fun maxY(r: OCRResult): Float = r.box.maxOf { it.y }
    private fun boxHeight(r: OCRResult): Float = calculateRotatedRectHeight(r.box)

    /** Returns the last capturing group of the given pattern on the line, or null. */
    private fun captureValue(line: String, pattern: Regex): String? {
        val m = pattern.find(line) ?: return null
        return m.groupValues.last().trim().ifEmpty { null }
    }

    /** Normalize dates like "01/08/2026" or "Aug 2026" and trim stray separators. */
    private fun cleanDate(v: String): String = v.trim(' ', '.', ':', ';', ',')

    /** True if a line looks like another declaration/label rather than free address text. */
    private fun isFieldLine(text: String): Boolean = fieldLabelRegex().containsMatchIn(text)

    private fun fieldLabelRegex(): Regex = Regex(
        "(?i)\\b(net\\s*(wt\\.?|weight|quantity|qty|content|volume|mass)?|" +
        "mrp\\b|max\\.?\\s*retail|maximum\\s+retail|retail\\s*price|\\bprice\\b|" +
        "mfg|mfd|manufactur(ed|ing|er)?|pack(ed|er|ing)?|market(ed)?\\s*by|" +
        "best\\s*(before|by|used)|use\\s*by|expiry|exp\\.?|" +
        "batch\\b|lot\\s*(no|number|#)?|b\\s*n\\s*o\\b|" +
        "country\\s*of\\s*origin|made\\s*in|imported|origin\\b|" +
        "customer\\s*care|helpline|toll\\s*free|consumer\\s*care|grievance|" +
        "phone\\b|tel\\.?|mobile\\b|contact\\b|call\\s*(us)?|" +
        "email|e-mail|@|www\\.|https?|website|" +
        "regd\\.?|registered|licence|license|fssai|isi\\s*mark|certificate|" +
        "ingred(ients)?|storage|directions\\s*for\\s*use|serving|" +
        "refund|replace(ment)?|offer|scheme|valid\\s*until)\\b"
    )

    private fun netQtyPattern(): Regex = Regex(
        "(?i)\\bnet\\s*(?:wt\\.?|weight|quantity|qty|content|volume|mass)?\\s*[=:.]?\\s*" +
        "([0-9]+(?:[.,][0-9]+)?\\s*(?:g|kg|gm|gr|gms|ml|l|lt|litres?|liters?|pcs|pieces|count|nos\\.?|nos|units))"
    )

    private fun mrpPattern(): Regex = Regex(
        "(?i)\\b(?:mrp|max\\.?\\s*retail\\s*(?:price)?|maximum\\s+retail\\s+price|retail\\s*price)\\s*" +
        "(?:\\([^)]*\\))?\\s*[=:.\\-]?\\s*(?:rs\\.?|inr|\\u20B9)?\\s*([0-9]+(?:[.,][0-9]+)?)"
    )

    private fun mfgDatePattern(): Regex = Regex(
        "(?i)\\b(?:mfg|mfd|manufactur(?:ed|ing)?|pack(?:ed|ing)?)\\s*(?:date|dt\\.?|on)?\\s*[=:.]?\\s*(" +
        "[0-9]{1,2}[/\\-][0-9]{1,2}[/\\-][0-9]{2,4}" +
        "|[0-9]{1,2}[/\\-][0-9]{2,4}" +
        "|[A-Za-z]{3,9}\\s*[.,]?\\s*[0-9]{4})"
    )

    private fun bestBeforePattern(): Regex = Regex(
        "(?i)\\b(?:best\\s*(?:before|by|used)|use\\s*by|best\\s*used\\s*before|expiry|exp\\.?)\\s*[=:.]?\\s*(" +
        "[0-9]{1,2}[/\\-][0-9]{1,2}[/\\-][0-9]{2,4}" +
        "|[0-9]{1,2}[/\\-][0-9]{2,4}" +
        "|[A-Za-z]{3,9}\\s*[.,]?\\s*[0-9]{4})"
    )

    private fun manufacturerPattern(): Regex = Regex(
        "(?i)\\b(?:mfd|mfg|manufactur(?:ed|er|ing)?|pack(?:ed|er)?|marketed\\s*by|marketer|distribut(?:ed|or)?)\\s*" +
        "(?:by|in)?\\s*[:.\\-]?\\s*([A-Za-z0-9][A-Za-z0-9&.,'\\- ]{2,60})"
    )

    private fun batchPattern(): Regex = Regex(
        "(?i)\\b(?:batch|lot|bn)\\s*(?:no|number|#|\\.)?\\s*[=:.]?\\s*([A-Za-z0-9][A-Za-z0-9/\\-]{1,20})"
    )

    private fun countryPattern(): Regex = Regex(
        "(?i)\\b(?:country\\s*of\\s*origin|made\\s*in|imported\\s*(?:from)?)\\s*[=:.]?\\s*([A-Za-z][A-Za-z\\s]{2,40})"
    )

    private fun carePattern(): Regex = Regex(
        "(?i)\\b(?:customer\\s*care|consumer\\s*care|helpline|toll\\s*free\\s*(?:no|number|line)?|grievance\\s*cell)\\s*" +
        "[=:.]?\\s*([0-9][0-9+\\-()\\s]{7,18})"
    )

    private fun phonePattern(): Regex = Regex(
        "(?i)\\b(?:phone|tel\\.?|mobile|contact|call\\s*(?:us)?)\\s*[=:.]?\\s*" +
        "([0-9+][0-9+\\-()\\s]{7,18})"
    )

    /**
     * Check all mandatory declarations per Legal Metrology Rules 2011
     */
    private fun checkMandatoryDeclarations(fields: ExtractedFields, fullText: String): List<ComplianceCheck> {
        val checks = mutableListOf<ComplianceCheck>()

        // 1. Product name / generic name
        checks.add(ComplianceCheck(
            rule = "Product/Generic Name (Rule 6(1)(a))",
            description = "Name of the commodity shall be declared",
            passed = fields.productName?.length ?: 0 >= 3,
            foundValue = fields.productName,
            severity = ComplianceSeverity.CRITICAL
        ))

        // 2. Net quantity
        checks.add(ComplianceCheck(
            rule = "Net Quantity (Rule 6(1)(b))",
            description = "Net quantity in weight/measure/number shall be declared",
            passed = fields.netQuantity != null,
            foundValue = fields.netQuantity,
            severity = ComplianceSeverity.CRITICAL
        ))

        // 3. MRP
        checks.add(ComplianceCheck(
            rule = "Maximum Retail Price (Rule 6(1)(c))",
            description = "MRP inclusive of all taxes shall be declared",
            passed = fields.mrp != null,
            foundValue = fields.mrp,
            severity = ComplianceSeverity.CRITICAL
        ))

        // 4. Manufacturing date
        checks.add(ComplianceCheck(
            rule = "Manufacturing Date (Rule 6(1)(d))",
            description = "Month and year of manufacture/packing/import shall be declared",
            passed = fields.mfgDate != null,
            foundValue = fields.mfgDate,
            severity = ComplianceSeverity.CRITICAL
        ))

        // 5. Best before / expiry (where applicable)
        val needsBestBefore = fullText.contains(Regex("(?i)best\\s*(before|by)|expir|use\\s*by"))
        checks.add(ComplianceCheck(
            rule = "Best Before/Expiry Date (Rule 6(1)(e))",
            description = "Best before/use by date where applicable",
            passed = !needsBestBefore || fields.bestBefore != null,
            foundValue = fields.bestBefore,
            severity = if (needsBestBefore) ComplianceSeverity.CRITICAL else ComplianceSeverity.ADVISORY
        ))

        // 6. Manufacturer name
        checks.add(ComplianceCheck(
            rule = "Manufacturer/Packer Name (Rule 6(1)(f))",
            description = "Name and address of manufacturer/packer/importer",
            passed = fields.manufacturer != null,
            foundValue = fields.manufacturer,
            severity = ComplianceSeverity.CRITICAL
        ))

        // 7. Manufacturer address
        checks.add(ComplianceCheck(
            rule = "Manufacturer Address (Rule 6(1)(f))",
            description = "Complete address of manufacturer/packer/importer",
            passed = fields.address != null,
            foundValue = fields.address,
            severity = ComplianceSeverity.CRITICAL
        ))

        // 8. Consumer care contact
        checks.add(ComplianceCheck(
            rule = "Consumer Care Details (Rule 6(1)(g))",
            description = "Consumer care address, phone number, email",
            passed = fields.customerCare != null || fields.phone != null || fields.email != null,
            foundValue = fields.customerCare ?: fields.phone ?: fields.email,
            severity = ComplianceSeverity.CRITICAL
        ))

        // 9. Country of origin (for imported goods)
        val isImported = fullText.contains(Regex("(?i)import(ed|er)"))
        checks.add(ComplianceCheck(
            rule = "Country of Origin (Rule 6(1)(h))",
            description = "Country of origin for imported commodities",
            passed = !isImported || fields.countryOfOrigin != null,
            foundValue = fields.countryOfOrigin,
            severity = if (isImported) ComplianceSeverity.CRITICAL else ComplianceSeverity.ADVISORY
        ))

        // 10. Batch/Lot number
        checks.add(ComplianceCheck(
            rule = "Batch/Lot Number (Rule 6(1)(i))",
            description = "Batch or lot number for traceability",
            passed = fields.batchNumber != null,
            foundValue = fields.batchNumber,
            severity = ComplianceSeverity.CRITICAL
        ))

        return checks
    }

    /**
     * Check font size compliance using ArUco calibration
     */
    private fun checkFontSizes(results: List<OCRResult>): List<ComplianceCheck> {
        val checks = mutableListOf<ComplianceCheck>()

        for (result in results) {
            // Calculate bounding box height in pixels
            val points = result.box
            if (points.size >= 4) {
                // Calculate actual text height from rotated rectangle (perpendicular distance between top and bottom edges)
                // PaddleOCR returns 4 points in order: top-left, top-right, bottom-right, bottom-left (clockwise)
                val heightPx = calculateRotatedRectHeight(points).toInt()
                val heightMm = heightPx * pixelToMmRatio

                val passed = heightMm >= minFontHeightMm
                checks.add(ComplianceCheck(
                    rule = "Font Size (Rule 6(2))",
                    description = "Minimum font height 1mm for mandatory declarations",
                    passed = passed,
                    foundValue = String.format("%.2f mm (%d px)", heightMm, heightPx),
                    severity = if (passed) ComplianceSeverity.PASS else ComplianceSeverity.CRITICAL,
                    text = result.text // Include the OCR text line for display
                ))
            }
        }

        return checks
    }

    /**
     * Calculate the height of a rotated rectangle from 4 ordered corner points.
     * Points expected in order: top-left, top-right, bottom-right, bottom-left (clockwise).
     * Returns the perpendicular distance between the top edge and bottom edge.
     */
    private fun calculateRotatedRectHeight(points: List<Point>): Float {
        // Top edge: points[0] to points[1]
        val topP1 = points[0]
        val topP2 = points[1]

        // Bottom edge: points[3] to points[2] (reversed for consistent direction)
        val bottomP1 = points[3]

        // Line equation for top edge: Ax + By + C = 0
        // A = y2 - y1, B = x1 - x2, C = x2*y1 - x1*y2
        val A = topP2.y - topP1.y
        val B = topP1.x - topP2.x
        val C = topP2.x * topP1.y - topP1.x * topP2.y

        // Distance from bottom-left point to top edge line
        // distance = |Ax0 + By0 + C| / sqrt(A^2 + B^2)
        val numerator = kotlin.math.abs(A * bottomP1.x + B * bottomP1.y + C)
        val denominator = kotlin.math.sqrt(A * A + B * B)

        return if (denominator > 0) {
            numerator / denominator
        } else {
            // Degenerate case (points too close), fallback to min/max Y
            val yValues = points.map { it.y }
            kotlin.math.abs(yValues.maxOrNull()!! - yValues.minOrNull()!!)
        }
    }

    companion object {
        fun createWithDefaults(pixelToMmRatio: Double): ComplianceEngine {
            return ComplianceEngine(pixelToMmRatio = pixelToMmRatio)
        }

        private val EMAIL_PATTERN = Regex("(?i)[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\\.[a-zA-Z]{2,}")
    }
}

/**
 * Single OCR result item
 */
data class OCRResult(
    val text: String,
    val confidence: Float,
    val box: List<Point>
)

/**
 * Point in image coordinates
 */
data class Point(
    val x: Float,
    val y: Float
)

/**
 * Extracted structured fields from label
 */
data class ExtractedFields(
    val productName: String?,
    val netQuantity: String?,
    val mrp: String?,
    val mfgDate: String?,
    val bestBefore: String?,
    val manufacturer: String?,
    val address: String?,
    val phone: String?,
    val email: String?,
    val customerCare: String?,
    val countryOfOrigin: String?,
    val batchNumber: String?
)

/**
 * Single compliance check result
 */
data class ComplianceCheck(
    val rule: String,
    val description: String,
    val passed: Boolean,
    val foundValue: Any?,
    val severity: ComplianceSeverity,
    val text: String? = null // Associated text line (used by font-size checks)
)

/**
 * Compliance severity levels
 */
enum class ComplianceSeverity {
    CRITICAL,    // Mandatory declaration missing
    WARNING,     // Format issue or minor violation
    ADVISORY,    // Recommended but not mandatory
    PASS         // Check passed
}

/**
 * Complete compliance report
 */
data class ComplianceReport(
    val overallCompliant: Boolean,
    val totalChecks: Int,
    val passedChecks: Int,
    val mandatoryChecks: List<ComplianceCheck>,
    val fontSizeChecks: List<ComplianceCheck>,
    val extractedFields: ExtractedFields,
    val minFontHeightPx: Int,
    val pixelToMmRatio: Double
) {
    val compliancePercentage: Double
        get() = if (totalChecks > 0) (passedChecks.toDouble() / totalChecks * 100) else 100.0

    val criticalFailures: List<ComplianceCheck>
        get() = (mandatoryChecks + fontSizeChecks).filter { it.severity == ComplianceSeverity.CRITICAL && !it.passed }

    val warnings: List<ComplianceCheck>
        get() = (mandatoryChecks + fontSizeChecks).filter { it.severity == ComplianceSeverity.WARNING && !it.passed }
}