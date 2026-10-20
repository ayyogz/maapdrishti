import 'dart:io';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:image_picker/image_picker.dart';

void main() {
  runApp(const MyApp());
}

class MyApp extends StatelessWidget {
  const MyApp({super.key});

  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      title: 'Legal Metrology Compliance',
      debugShowCheckedModeBanner: false,
      theme: ThemeData(
        colorScheme: ColorScheme.fromSeed(seedColor: Colors.deepPurple),
        useMaterial3: true,
        fontFamily: 'Roboto',
        listTileTheme: const ListTileThemeData(
          contentPadding: EdgeInsets.symmetric(horizontal: 16, vertical: 4),
          dense: true,
        ),
        cardTheme: CardThemeData(
          elevation: 0,
          shape: RoundedRectangleBorder(
            borderRadius: BorderRadius.circular(12),
            side: BorderSide(color: Colors.grey.shade200),
          ),
          margin: EdgeInsets.zero,
        ),
        dividerTheme: DividerThemeData(
          color: Colors.grey.shade200,
          thickness: 1,
          space: 1,
        ),
        chipTheme: ChipThemeData(
          labelStyle: const TextStyle(fontSize: 12, fontWeight: FontWeight.w500),
          padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 4),
          shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(20)),
          side: BorderSide(color: Colors.grey.shade300),
        ),
      ),
      home: const CompliancePage(),
    );
  }
}

class CompliancePage extends StatefulWidget {
  const CompliancePage({super.key});

  @override
  State<CompliancePage> createState() => _CompliancePageState();
}

class _CompliancePageState extends State<CompliancePage> with SingleTickerProviderStateMixin {
  static const platform = MethodChannel('com.sih.legmet/paddleocr');
  final ImagePicker _picker = ImagePicker();

  bool _isInitialized = false;
  bool _isProcessing = false;
  String _status = 'Initializing…';
  XFile? _selectedImage;

  List<Map<String, dynamic>> _ocrResults = [];
  Map<String, dynamic>? _complianceResult;

  late TabController _tabController;

  @override
  void initState() {
    super.initState();
    _tabController = TabController(length: 3, vsync: this);
    _initializeOCR();
  }

  @override
  void dispose() {
    _tabController.dispose();
    super.dispose();
  }

  Future<void> _initializeOCR() async {
    try {
      final result = await platform.invokeMethod('initialize');
      setState(() {
        _isInitialized = result['success'] as bool;
        _status = _isInitialized
            ? 'Ready · cold load ${result['coldLoadTimeMs']}ms'
            : 'Initialization failed';
      });
      if (_isInitialized && mounted) _runAutoTest();
    } on PlatformException catch (e) {
      setState(() => _status = 'Error: ${e.message}');
    }
  }

  Future<void> _runAutoTest() async {
    const testPath = '/data/local/tmp/test_images/test_image_aruco.jpg';
    if (mounted) {
      setState(() {
        _selectedImage = XFile(testPath);
        _ocrResults = [];
        _complianceResult = null;
      });
      await _runFullTest();
    }
  }

  Future<void> _pickImage() async {
    final XFile? image = await _picker.pickImage(source: ImageSource.gallery);
    if (image != null) {
      setState(() {
        _selectedImage = image;
        _ocrResults = [];
        _complianceResult = null;
      });
    }
  }

  Future<void> _runFullTest() async {
    if (_selectedImage == null) return;
    setState(() {
      _isProcessing = true;
      _status = 'Running OCR + ArUco + Compliance…';
      _ocrResults = [];
      _complianceResult = null;
    });
    try {
      // 1. Run OCR
      final ocrResult = await platform.invokeMethod('recognize', {
        'imagePath': _selectedImage!.path,
      });
      final rawResults = ocrResult['results'] as List;
      final ocrResults = rawResults.map((e) => Map<String, dynamic>.from(e as Map)).toList();

      // 2. Run ArUco
      final arucoResult = await platform.invokeMethod('detectAruco', {
        'imagePath': _selectedImage!.path,
        'markerLengthMm': 20.0,
      });
      final arucoSuccess = arucoResult['success'] as bool;
      final pixelToMmRatio = arucoSuccess
          ? (arucoResult['pixelToMmRatio'] as double)
          : null; // null = no calibration, font sizes will be skipped

      // 3. Run Compliance — ALWAYS run mandatory checks; font sizes only if calibrated
      final complianceResult = await platform.invokeMethod('checkCompliance', {
        'imagePath': _selectedImage!.path,
        'pixelToMmRatio': pixelToMmRatio, // can be null
        'markerLengthMm': 20.0,
      });

      setState(() {
        _ocrResults = ocrResults;
        _complianceResult = Map<String, dynamic>.from(complianceResult as Map);
        _isProcessing = false;

        final compliant = _complianceResult!['overallCompliant'] as bool;
        final pct = (_complianceResult!['compliancePercentage'] as double).toStringAsFixed(1);
        _status = compliant ? 'COMPLIANT ($pct%)' : 'NON-COMPLIANT ($pct%)';

        if (pixelToMmRatio == null && !compliant) {
          // Prepend calibration warning if ArUco was missing
          final existingFailures = List<String>.from(_complianceResult!['criticalFailures'] ?? []);
          if (!existingFailures.any((f) => f.contains('ArUco'))) {
            existingFailures.insert(0, 'ArUco marker not detected — font size validation skipped');
            _complianceResult!['criticalFailures'] = existingFailures;
          }
        }
      });

      setState(() {
        _ocrResults = ocrResults;
        _complianceResult = Map<String, dynamic>.from(complianceResult as Map);
        _isProcessing = false;
        final compliant = _complianceResult!['overallCompliant'] as bool;
        final pct = (_complianceResult!['compliancePercentage'] as double).toStringAsFixed(1);
        _status = compliant ? 'COMPLIANT ($pct%)' : 'NON-COMPLIANT ($pct%)';
      });
    } on PlatformException catch (e) {
      setState(() => _status = 'Error: ${e.message}');
      _isProcessing = false;
    }
  }

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final cs = theme.colorScheme;

    return Scaffold(
      appBar: AppBar(
        title: const Text('Legal Metrology Compliance'),
        backgroundColor: cs.surface,
        bottom: _complianceResult != null
            ? PreferredSize(
                preferredSize: const Size.fromHeight(48),
                child: _TabBar(controller: _tabController),
              )
            : null,
      ),
      body: Padding(
        padding: const EdgeInsets.all(16.0),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            // Status bar
            _StatusBar(
              isInitialized: _isInitialized,
              status: _status,
              isProcessing: _isProcessing,
            ),
            const SizedBox(height: 16),

            // Action buttons
            _ActionButtons(
              selectedImage: _selectedImage,
              isProcessing: _isProcessing,
              isInitialized: _isInitialized,
              onPickImage: _pickImage,
              onRunTest: _runFullTest,
            ),
            const SizedBox(height: 20),

            // Results area
            Expanded(
              child: _buildResults(theme),
            ),
          ],
        ),
      ),
    );
  }

  Widget _buildResults(ThemeData theme) {
    if (_selectedImage == null) {
      return _EmptyState(theme: theme);
    }

    if (_isProcessing) {
      return const Center(child: CircularProgressIndicator());
    }

    if (_complianceResult == null) {
      return _ImagePreview(image: _selectedImage!, theme: theme);
    }

    // After test - the verdict header scrolls away with the content instead
    // of permanently reserving space, so the tab bar (pinned in the AppBar)
    // stays fixed while the tab content always gets the full remaining height.
    return NestedScrollView(
      headerSliverBuilder: (context, innerBoxIsScrolled) => [
        SliverToBoxAdapter(
          child: Padding(
            padding: const EdgeInsets.only(bottom: 16),
            child: _VerdictHeader(
              complianceResult: _complianceResult!,
              theme: theme,
            ),
          ),
        ),
      ],
      body: TabBarView(
        controller: _tabController,
        children: [
          _OverviewTab(complianceResult: _complianceResult!, theme: theme),
          _OCRTextTab(ocrResults: _ocrResults, theme: theme),
          _FontSizesTab(fontSizeChecks: _complianceResult!['fontSizeChecks'] as List?, theme: theme),
        ],
      ),
    );
  }
}

/// ==================== REUSABLE WIDGETS ====================

class _StatusBar extends StatelessWidget {
  final bool isInitialized;
  final String status;
  final bool isProcessing;

  const _StatusBar({
    required this.isInitialized,
    required this.status,
    required this.isProcessing,
  });

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final cs = theme.colorScheme;

    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 10),
      decoration: BoxDecoration(
        color: cs.surfaceContainerHighest,
        borderRadius: BorderRadius.circular(10),
      ),
      child: Row(
        children: [
          Icon(
            isInitialized ? Icons.check_circle_outline : Icons.hourglass_empty,
            size: 20,
            color: isInitialized ? Colors.green.shade700 : Colors.orange.shade700,
          ),
          const SizedBox(width: 10),
          Expanded(
            child: Text(
              status,
              style: const TextStyle(fontSize: 14, fontWeight: FontWeight.w500),
              overflow: TextOverflow.ellipsis,
            ),
          ),
          if (isProcessing)
            const SizedBox(
              width: 20,
              height: 20,
              child: CircularProgressIndicator(strokeWidth: 2),
            ),
        ],
      ),
    );
  }
}

class _ActionButtons extends StatelessWidget {
  final XFile? selectedImage;
  final bool isProcessing;
  final bool isInitialized;
  final VoidCallback onPickImage;
  final VoidCallback onRunTest;

  const _ActionButtons({
    required this.selectedImage,
    required this.isProcessing,
    required this.isInitialized,
    required this.onPickImage,
    required this.onRunTest,
  });

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final cs = theme.colorScheme;

    return Row(
      children: [
        Expanded(
          child: FilledButton.tonalIcon(
            onPressed: isProcessing ? null : onPickImage,
            icon: const Icon(Icons.photo_library_outlined, size: 22),
            label: const Text('Select Image', style: TextStyle(fontSize: 16)),
            style: FilledButton.styleFrom(
              padding: const EdgeInsets.symmetric(vertical: 16),
              backgroundColor: cs.primaryContainer,
              foregroundColor: cs.onPrimaryContainer,
              shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(12)),
            ),
          ),
        ),
        const SizedBox(width: 12),
        Expanded(
          child: FilledButton.icon(
            onPressed: (selectedImage == null || isProcessing || !isInitialized)
                ? null
                : onRunTest,
            icon: const Icon(Icons.play_arrow_rounded, size: 22),
            label: const Text('Analyze', style: TextStyle(fontSize: 16, fontWeight: FontWeight.w600)),
            style: FilledButton.styleFrom(
              padding: const EdgeInsets.symmetric(vertical: 16),
              backgroundColor: cs.primary,
              foregroundColor: cs.onPrimary,
              shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(12)),
            ),
          ),
        ),
      ],
    );
  }
}

class _TabBar extends StatelessWidget implements PreferredSizeWidget {
  final TabController controller;

  const _TabBar({required this.controller});

  @override
  Size get preferredSize => const Size.fromHeight(48);

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final cs = theme.colorScheme;

    return Container(
      height: 48,
      decoration: BoxDecoration(
        color: cs.surfaceContainerHighest,
        borderRadius: BorderRadius.circular(12),
      ),
      margin: const EdgeInsets.symmetric(horizontal: 16, vertical: 8),
      child: TabBar(
        controller: controller,
        indicatorSize: TabBarIndicatorSize.tab,
        dividerColor: Colors.transparent,
        indicator: BoxDecoration(
          color: cs.primary,
          borderRadius: BorderRadius.circular(10),
        ),
        labelColor: cs.onPrimary,
        unselectedLabelColor: cs.onSurfaceVariant,
        labelStyle: const TextStyle(fontSize: 13, fontWeight: FontWeight.w600),
        unselectedLabelStyle: const TextStyle(fontSize: 13, fontWeight: FontWeight.w500),
        tabs: const [
          Tab(icon: Icon(Icons.dashboard_outlined, size: 18), text: 'Overview'),
          Tab(icon: Icon(Icons.text_snippet_outlined, size: 18), text: 'OCR Text'),
          Tab(icon: Icon(Icons.format_size_outlined, size: 18), text: 'Font Sizes'),
        ],
      ),
    );
  }
}

class _EmptyState extends StatelessWidget {
  final ThemeData theme;
  const _EmptyState({required this.theme});

  @override
  Widget build(BuildContext context) {
    final cs = theme.colorScheme;
    return Center(
      child: Column(
        mainAxisAlignment: MainAxisAlignment.center,
        children: [
          Icon(Icons.camera_alt_outlined, size: 64, color: cs.outline),
          const SizedBox(height: 16),
          Text('Select a label image to begin',
              style: TextStyle(fontSize: 16, color: cs.onSurfaceVariant)),
        ],
      ),
    );
  }
}

class _ImagePreview extends StatelessWidget {
  final XFile image;
  final ThemeData theme;
  const _ImagePreview({required this.image, required this.theme});

  @override
  Widget build(BuildContext context) {
    final cs = theme.colorScheme;
    return Card(
      child: Padding(
        padding: const EdgeInsets.all(16),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Row(
              children: [
                ClipRRect(
                  borderRadius: BorderRadius.circular(8),
                  child: Image.file(
                    File(image.path),
                    width: 80, height: 80, fit: BoxFit.cover,
                  ),
                ),
                const SizedBox(width: 16),
                Expanded(
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      Text('Image Selected', style: TextStyle(fontWeight: FontWeight.w600, fontSize: 16, color: cs.onSurface)),
                      Text('Tap "Analyze" to run OCR + ArUco + Compliance',
                          style: TextStyle(fontSize: 13, color: cs.onSurfaceVariant)),
                    ],
                  ),
                ),
              ],
            ),
          ],
        ),
      ),
    );
  }
}

class _VerdictHeader extends StatelessWidget {
  final Map<String, dynamic> complianceResult;
  final ThemeData theme;

  const _VerdictHeader({required this.complianceResult, required this.theme});

  @override
  Widget build(BuildContext context) {
    final cs = theme.colorScheme;
    final compliant = complianceResult['overallCompliant'] as bool? ?? false;
    final pct = (complianceResult['compliancePercentage'] as double?) ?? 0.0;
    final passed = complianceResult['passedChecks'] as int? ?? 0;
    final total = complianceResult['totalChecks'] as int? ?? 0;
    final fields = (complianceResult['extractedFields'] as Map?) ?? {};

    return Card(
      color: compliant ? Colors.green.shade50 : Colors.red.shade50,
      child: Padding(
        padding: const EdgeInsets.all(16),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            // Verdict row
            Row(
              children: [
                Icon(
                  compliant ? Icons.verified_rounded : Icons.warning_amber_rounded,
                  size: 28,
                  color: compliant ? Colors.green.shade700 : Colors.red.shade700,
                ),
                const SizedBox(width: 12),
                Expanded(
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      Text(
                        compliant ? 'COMPLIANT' : 'NON-COMPLIANT',
                        style: TextStyle(
                          fontSize: 20,
                          fontWeight: FontWeight.w700,
                          color: compliant ? Colors.green.shade700 : Colors.red.shade700,
                        ),
                      ),
                      const SizedBox(height: 2),
                      Text('$pct% · $passed/$total checks passed',
                          style: TextStyle(fontSize: 13, color: cs.onSurfaceVariant)),
                    ],
                  ),
                ),
              ],
            ),
            const SizedBox(height: 16),
            // Compliance progress bar
            LinearProgressIndicator(
              value: pct / 100,
              minHeight: 6,
              borderRadius: BorderRadius.circular(3),
              backgroundColor: compliant ? Colors.green.shade100 : Colors.red.shade100,
              valueColor: AlwaysStoppedAnimation(compliant ? Colors.green.shade700 : Colors.red.shade700),
            ),
            const SizedBox(height: 16),
            // Extracted fields - organized in 2-column grid
            if (fields.isNotEmpty) ...[
              Text('Extracted Fields', style: TextStyle(fontSize: 13, fontWeight: FontWeight.w600, color: cs.onSurfaceVariant)),
              const SizedBox(height: 8),
              _FieldsGrid(fields: fields, theme: theme),
            ],
          ],
        ),
      ),
    );
  }
}

class _FieldsGrid extends StatelessWidget {
  final Map fields;
  final ThemeData theme;

  const _FieldsGrid({required this.fields, required this.theme});

  @override
  Widget build(BuildContext context) {
    final cs = theme.colorScheme;
    final entries = fields.entries
        .where((e) => e.value.toString() != 'NOT FOUND' && e.value.toString().isNotEmpty)
        .toList();

    if (entries.isEmpty) return const SizedBox.shrink();

    return Wrap(
      spacing: 8,
      runSpacing: 8,
      children: entries.map((e) {
        return Chip(
          label: Text(
            '${_formatFieldKey(e.key)}: ${e.value}',
            style: TextStyle(fontSize: 11, fontFamily: 'monospace', color: cs.onSurface),
            overflow: TextOverflow.ellipsis,
          ),
          backgroundColor: cs.surfaceContainerHighest,
          side: BorderSide(color: cs.outlineVariant),
          visualDensity: VisualDensity.compact,
        );
      }).toList(),
    );
  }

  String _formatFieldKey(String key) {
    return key.replaceAllMapped(RegExp(r'([A-Z])'), (m) => ' ${m.group(0)}').trim();
  }
}

class _OverviewTab extends StatelessWidget {
  final Map<String, dynamic> complianceResult;
  final ThemeData theme;

  const _OverviewTab({required this.complianceResult, required this.theme});

  @override
  Widget build(BuildContext context) {
    final cs = theme.colorScheme;
    final mandatoryChecks = (complianceResult['mandatoryChecks'] as List?) ?? [];
    final fontSizeChecks = (complianceResult['fontSizeChecks'] as List?) ?? [];
    final criticalFailures = (complianceResult['criticalFailures'] as List?) ?? [];

    return ListView(
      padding: const EdgeInsets.only(bottom: 24),
      children: [
        // Critical failures section (if any)
        if (criticalFailures.isNotEmpty) ...[
          _SectionHeader(title: 'Critical Failures', icon: Icons.error_outline, color: Colors.red.shade700),
          const SizedBox(height: 8),
          ...criticalFailures.map((c) => _CheckCard(
            title: c['rule']?.toString() ?? '',
            subtitle: c['description']?.toString() ?? '',
            value: c['foundValue']?.toString() ?? 'NOT FOUND',
            passed: false,
            severityColor: Colors.red.shade700,
            theme: theme,
          )),
          const SizedBox(height: 16),
        ],

        // Mandatory declarations
        _SectionHeader(title: 'Mandatory Declarations', icon: Icons.assignment_outlined, color: cs.primary),
        const SizedBox(height: 8),
        ...mandatoryChecks.map((c) => _CheckCard(
          title: c['rule']?.toString() ?? '',
          subtitle: c['description']?.toString() ?? '',
          value: c['foundValue']?.toString() ?? 'NOT FOUND',
          passed: c['passed'] == true,
          severityColor: (c['passed'] == true) ? Colors.green.shade700 : Colors.red.shade700,
          theme: theme,
        )),

        const SizedBox(height: 16),

        // Font size checks
        if (fontSizeChecks.isNotEmpty) ...[
          _SectionHeader(title: 'Font Size Compliance (Rule 6(2))', icon: Icons.format_size_outlined, color: Colors.orange.shade700),
          const SizedBox(height: 8),
          ...fontSizeChecks.map((c) => _CheckCard(
            title: c['rule']?.toString() ?? '',
            subtitle: c['text']?.toString() ?? '(no text)',
            value: c['foundValue']?.toString() ?? 'N/A',
            passed: c['passed'] == true,
            severityColor: (c['passed'] == true) ? Colors.green.shade700 : Colors.red.shade700,
            theme: theme,
          )),
        ],
      ],
    );
  }
}

class _SectionHeader extends StatelessWidget {
  final String title;
  final IconData icon;
  final Color color;

  const _SectionHeader({required this.title, required this.icon, required this.color});

  @override
  Widget build(BuildContext context) {
    return Row(
      children: [
        Icon(icon, size: 18, color: color),
        const SizedBox(width: 8),
        Text(title, style: TextStyle(fontSize: 14, fontWeight: FontWeight.w600, color: color)),
      ],
    );
  }
}

class _CheckCard extends StatelessWidget {
  final String title;
  final String subtitle;
  final String value;
  final bool passed;
  final Color severityColor;
  final ThemeData theme;

  const _CheckCard({
    required this.title,
    required this.subtitle,
    required this.value,
    required this.passed,
    required this.severityColor,
    required this.theme,
  });

  @override
  Widget build(BuildContext context) {
    final cs = theme.colorScheme;

    return Card(
      margin: const EdgeInsets.only(bottom: 8),
      child: Padding(
        padding: const EdgeInsets.all(14),
        child: Row(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Icon(
              passed ? Icons.check_circle_outline : Icons.cancel_outlined,
              color: severityColor,
              size: 22,
            ),
            const SizedBox(width: 12),
            Expanded(
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Text(title, style: const TextStyle(fontSize: 13, fontWeight: FontWeight.w600)),
                  const SizedBox(height: 2),
                  Text(subtitle, style: TextStyle(fontSize: 11, color: cs.onSurfaceVariant)),
                  const SizedBox(height: 4),
                  Text(
                    value,
                    style: TextStyle(
                      fontSize: 12,
                      fontFamily: 'monospace',
                      fontWeight: FontWeight.w500,
                      color: severityColor,
                    ),
                    overflow: TextOverflow.ellipsis,
                    maxLines: 1,
                  ),
                ],
              ),
            ),
            Container(
              padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 4),
              decoration: BoxDecoration(
                color: passed ? Colors.green.shade50 : Colors.red.shade50,
                borderRadius: BorderRadius.circular(16),
              ),
              child: Text(
                passed ? 'PASS' : 'FAIL',
                style: TextStyle(
                  fontSize: 10,
                  fontWeight: FontWeight.w700,
                  color: passed ? Colors.green.shade700 : Colors.red.shade700,
                ),
              ),
            ),
          ],
        ),
      ),
    );
  }
}

class _OCRTextTab extends StatelessWidget {
  final List<Map<String, dynamic>> ocrResults;
  final ThemeData theme;

  const _OCRTextTab({required this.ocrResults, required this.theme});

  @override
  Widget build(BuildContext context) {
    final cs = theme.colorScheme;

    if (ocrResults.isEmpty) {
      return Center(child: Text('No text detected', style: TextStyle(color: cs.onSurfaceVariant)));
    }

    return ListView.separated(
      padding: const EdgeInsets.only(bottom: 24),
      itemCount: ocrResults.length,
      separatorBuilder: (_, __) => Divider(height: 1, color: cs.outlineVariant),
      itemBuilder: (context, index) {
        final item = ocrResults[index];
        final text = item['text']?.toString() ?? '';
        final confidence = (item['confidence'] as double?) ?? 0.0;
        final box = (item['box'] as List?) ?? [];

        return ListTile(
          title: Text(text, style: const TextStyle(fontSize: 14, fontWeight: FontWeight.w500)),
          subtitle: Text(
            'Confidence: ${confidence.toStringAsFixed(2)} · ${box.length} pts',
            style: TextStyle(fontSize: 11, color: cs.onSurfaceVariant),
          ),
          leading: CircleAvatar(
            radius: 14,
            backgroundColor: cs.primaryContainer,
            child: Text('#${index + 1}', style: TextStyle(fontSize: 11, color: cs.onPrimaryContainer, fontWeight: FontWeight.w600)),
          ),
        );
      },
    );
  }
}

class _FontSizesTab extends StatelessWidget {
  final List<dynamic>? fontSizeChecks;
  final ThemeData theme;

  const _FontSizesTab({required this.fontSizeChecks, required this.theme});

  @override
  Widget build(BuildContext context) {
    final cs = theme.colorScheme;
    final checks = fontSizeChecks ?? [];

    if (checks.isEmpty) {
      return Center(child: Text('No font measurements available', style: TextStyle(color: cs.onSurfaceVariant)));
    }

    return ListView.separated(
      padding: const EdgeInsets.only(bottom: 24),
      itemCount: checks.length,
      separatorBuilder: (_, __) => Divider(height: 1, color: cs.outlineVariant),
      itemBuilder: (context, index) {
        final check = checks[index];
        final passed = check['passed'] == true;
        final foundValue = check['foundValue']?.toString() ?? 'N/A';
        final text = check['text']?.toString() ?? '';

        return ListTile(
          leading: Icon(
            passed ? Icons.check_circle_outline : Icons.cancel_outlined,
            color: passed ? Colors.green.shade700 : Colors.red.shade700,
            size: 24,
          ),
          title: Text(
            text.isNotEmpty ? text : '(no text)',
            style: const TextStyle(fontSize: 14, fontWeight: FontWeight.w500),
          ),
          subtitle: Text(
            foundValue,
            style: TextStyle(
              fontSize: 12,
              fontFamily: 'monospace',
              color: passed ? Colors.green.shade700 : Colors.red.shade700,
              fontWeight: FontWeight.w500,
            ),
          ),
          trailing: Container(
            padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 4),
            decoration: BoxDecoration(
              color: passed ? Colors.green.shade50 : Colors.red.shade50,
              borderRadius: BorderRadius.circular(16),
            ),
            child: Text(
              passed ? 'PASS' : 'FAIL',
              style: TextStyle(
                fontSize: 10,
                fontWeight: FontWeight.w700,
                color: passed ? Colors.green.shade700 : Colors.red.shade700,
              ),
            ),
          ),
        );
      },
    );
  }
}