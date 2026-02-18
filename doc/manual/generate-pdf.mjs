/**
 * HTML → PDF 변환 스크립트
 * 역할별 메뉴얼 6개 HTML → 6개 PDF 생성
 *
 * Usage: node generate-pdf.mjs
 */
import puppeteer from 'puppeteer';
import path from 'path';
import { fileURLToPath } from 'url';

const __dirname = path.dirname(fileURLToPath(import.meta.url));

const files = [
  { html: 'common/intro.html',      pdf: 'pdf/00-플랫폼-소개.pdf' },
  { html: 'common/chatbot.html',    pdf: 'pdf/01-AI-챗봇-가이드.pdf' },
  { html: 'common/appendix.html',   pdf: 'pdf/02-부록.pdf' },
  { html: 'applicant/index.html',   pdf: 'pdf/03-신청자-가이드.pdf' },
  { html: 'lew/index.html',         pdf: 'pdf/04-LEW-가이드.pdf' },
  { html: 'admin/index.html',       pdf: 'pdf/05-관리자-가이드.pdf' },
];

async function main() {
  const browser = await puppeteer.launch({ headless: true });
  const page = await browser.newPage();

  // A4 크기에 맞춘 뷰포트
  await page.setViewport({ width: 1280, height: 900 });

  for (const { html, pdf } of files) {
    const htmlPath = path.resolve(__dirname, html);
    const pdfPath  = path.resolve(__dirname, pdf);
    const fileUrl  = `file://${htmlPath}`;

    console.log(`Converting: ${html}`);
    await page.goto(fileUrl, { waitUntil: 'networkidle0', timeout: 30000 });

    // 이미지 로딩 대기
    await page.evaluate(() => {
      return Promise.all(
        Array.from(document.images)
          .filter(img => !img.complete)
          .map(img => new Promise(resolve => {
            img.onload = img.onerror = resolve;
          }))
      );
    });

    await page.pdf({
      path: pdfPath,
      format: 'A4',
      printBackground: true,
      margin: { top: '20mm', right: '18mm', bottom: '25mm', left: '18mm' },
      displayHeaderFooter: true,
      headerTemplate: '<span></span>',
      footerTemplate: `
        <div style="width:100%; text-align:center; font-size:8pt; color:#94a3b8; padding: 0 18mm;">
          <span class="pageNumber"></span> / <span class="totalPages"></span>
        </div>
      `,
    });

    console.log(`  ✓ ${pdf}`);
  }

  await browser.close();
  console.log('\nDone! All PDFs generated in pdf/ directory.');
}

main().catch(err => { console.error(err); process.exit(1); });
