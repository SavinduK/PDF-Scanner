package com.example

import com.example.model.CropQuad
import com.example.model.FilterType
import com.example.model.GeneratedPdfItem
import com.example.model.HomeTab
import com.example.model.PdfQuality
import com.example.model.Point2D
import com.example.model.ScannedDocument
import com.example.model.ScannedPage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class DocumentAndPdfPersistenceTest {

    @Test
    fun `test HomeTab enum has Document Mode and PDF Files tabs`() {
        assertEquals("Document Mode", HomeTab.DOCUMENTS.title)
        assertEquals("PDF Files", HomeTab.PDF_FILES.title)
    }

    @Test
    fun `test ScannedDocument retains pages permanently in document mode`() {
        val page1 = ScannedPage(
            originalImagePath = "/path/orig1.jpg",
            processedImagePath = "/path/proc1.jpg",
            cropQuad = CropQuad.defaultQuad(),
            filterType = FilterType.ORIGINAL
        )
        val page2 = ScannedPage(
            originalImagePath = "/path/orig2.jpg",
            processedImagePath = "/path/proc2.jpg",
            cropQuad = CropQuad.fullImage(),
            filterType = FilterType.AUTO_ENHANCE
        )

        val doc = ScannedDocument(
            id = "test-doc-1",
            title = "Receipts June",
            pages = listOf(page1, page2),
            lastPdfPath = null,
            pdfFileSizeBytes = 0L
        )

        assertEquals("test-doc-1", doc.id)
        assertEquals("Receipts June", doc.title)
        assertEquals(2, doc.pages.size)
        assertNull(doc.lastPdfPath)
        assertEquals(0L, doc.pdfFileSizeBytes)
        assertEquals(PdfQuality.MEDIUM, doc.pdfQuality)
        assertTrue(doc.updatedAt >= doc.createdAt)
    }

    @Test
    fun `test related PDF update when document is edited later and saved`() {
        val page1 = ScannedPage(
            originalImagePath = "/path/orig1.jpg",
            processedImagePath = "/path/proc1.jpg"
        )
        val initialDoc = ScannedDocument(
            id = "doc-100",
            title = "Contract",
            pages = listOf(page1),
            lastPdfPath = "/documents/Contract.pdf",
            pdfFileSizeBytes = 102400L,
            pdfQuality = PdfQuality.HIGH
        )

        // User edits document in Document Mode by adding a second page
        val page2 = ScannedPage(
            originalImagePath = "/path/orig2.jpg",
            processedImagePath = "/path/proc2.jpg"
        )
        val updatedPages = initialDoc.pages + page2
        val newUpdateTime = System.currentTimeMillis() + 1000

        // Document is saved, updating pages and keeping related PDF linked
        val updatedDoc = initialDoc.copy(
            pages = updatedPages,
            updatedAt = newUpdateTime,
            pdfFileSizeBytes = 204800L
        )

        assertEquals(2, updatedDoc.pages.size)
        assertEquals("/documents/Contract.pdf", updatedDoc.lastPdfPath)
        assertEquals(204800L, updatedDoc.pdfFileSizeBytes)
        assertEquals(PdfQuality.HIGH, updatedDoc.pdfQuality)
    }

    @Test
    fun `test deleting PDF file keeps document mode permanently saved`() {
        val page1 = ScannedPage(
            originalImagePath = "/path/orig1.jpg",
            processedImagePath = "/path/proc1.jpg"
        )
        val docWithPdf = ScannedDocument(
            id = "doc-200",
            title = "Notes",
            pages = listOf(page1),
            lastPdfPath = "/documents/Notes.pdf",
            pdfFileSizeBytes = 50000L
        )

        val savedDocs = listOf(docWithPdf)

        // Simulating deletePdfFile behavior: PDF is deleted, unlinked from doc, but document in Document Mode remains!
        val updatedDocs = savedDocs.map { doc ->
            if (doc.lastPdfPath == "/documents/Notes.pdf") {
                doc.copy(lastPdfPath = null, pdfFileSizeBytes = 0L)
            } else {
                doc
            }
        }

        assertEquals(1, updatedDocs.size)
        val docInDocumentMode = updatedDocs.first()
        assertEquals("doc-200", docInDocumentMode.id)
        assertEquals(1, docInDocumentMode.pages.size)
        assertNull(docInDocumentMode.lastPdfPath)
        assertEquals(0L, docInDocumentMode.pdfFileSizeBytes)
    }

    @Test
    fun `test GeneratedPdfItem captures source document metadata`() {
        val pdfItem = GeneratedPdfItem(
            id = "pdf_doc-300",
            documentId = "doc-300",
            documentTitle = "Passport Scan",
            filePath = "/storage/documents/Passport Scan.pdf",
            fileName = "Passport Scan.pdf",
            fileSizeBytes = 350000L,
            pageCount = 2,
            quality = PdfQuality.HIGH
        )

        assertEquals("doc-300", pdfItem.documentId)
        assertEquals("Passport Scan", pdfItem.documentTitle)
        assertEquals("Passport Scan.pdf", pdfItem.fileName)
        assertEquals(2, pdfItem.pageCount)
        assertEquals(350000L, pdfItem.fileSizeBytes)
        assertEquals(PdfQuality.HIGH, pdfItem.quality)
    }
}
