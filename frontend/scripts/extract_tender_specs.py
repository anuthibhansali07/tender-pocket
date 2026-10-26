#!/usr/bin/env python3
"""
Tender Specification & Metadata Extractor
Extracts authentic tender specifications, BOQ line items, and tender metadata
directly from the original GeM Bid Document PDF.
"""

import os
import sys
import re
import json
import argparse
import warnings

warnings.filterwarnings("ignore")
if hasattr(sys.stdout, 'reconfigure'):
    sys.stdout.reconfigure(encoding='utf-8')

try:
    import pymupdf
    pymupdf.TOOLS.mupdf_display_errors(False)
except Exception:
    try:
        import fitz as pymupdf
        pymupdf.TOOLS.mupdf_display_errors(False)
    except Exception:
        pymupdf = None

def find_pdf_for_tender(tender_id, custom_path=None):
    if custom_path and os.path.exists(custom_path):
        return custom_path

    candidates = [
        os.path.join("backend", "public", "documents", str(tender_id)),
        os.path.join("..", "backend", "public", "documents", str(tender_id)),
        os.path.join(os.path.dirname(__file__), "..", "..", "backend", "public", "documents", str(tender_id)),
        os.path.join("frontend", "public", "documents", str(tender_id)),
        os.path.join(os.path.dirname(__file__), "..", "public", "documents", str(tender_id)),
        os.path.join("public", "documents", str(tender_id)),
        os.path.join("..", "public", "documents", str(tender_id))
    ]

    for c_dir in candidates:
        if os.path.isdir(c_dir):
            exact = os.path.join(c_dir, f"Bid_Document_{tender_id}.pdf")
            if os.path.exists(exact):
                return exact
            for f in os.listdir(c_dir):
                if f.lower().endswith(".pdf") and not f.startswith("Bid_Documents_") and not f.startswith("Technical_Specification_Sheet_"):
                    return os.path.join(c_dir, f)
    return None

def extract_tender_data_and_specs(tender_id, pdf_path=None):
    if not pymupdf:
        return {
            "success": False,
            "error": "PyMuPDF not installed in python environment",
            "specs": []
        }

    path_to_use = find_pdf_for_tender(tender_id, pdf_path)
    if not path_to_use or not os.path.exists(path_to_use):
        return {
            "success": False,
            "error": f"Tender document PDF not found for ID: {tender_id}",
            "specs": []
        }

    try:
        doc = pymupdf.open(path_to_use)
    except Exception as e:
        return {
            "success": False,
            "error": f"Failed to open PDF: {str(e)}",
            "specs": []
        }

    total_pages = len(doc)
    p1_text = doc[0].get_text() if total_pages > 0 else ""
    p2_text = doc[1].get_text() if total_pages > 1 else ""
    meta_text = p1_text + "\n" + p2_text

    # 1. Metadata Extraction
    bid_no_m = re.search(r'(?:Bid Number|बड सं>या|Bid No\.?)\s*:\s*([A-Za-z0-9\/\-_]+)', meta_text, re.IGNORECASE)
    bid_number = bid_no_m.group(1).strip() if bid_no_m else ""

    dated_m = re.search(r'(?:Dated|दनांक|Date)\s*:\s*(\d{2}[\/\-]\d{2}[\/\-]\d{4})', meta_text, re.IGNORECASE)
    bid_date = dated_m.group(1).strip() if dated_m else ""

    qty_m = re.search(r'(?:Total Quantity|कुल मा ा)\s*\n\s*(\d+)', meta_text, re.IGNORECASE)
    total_qty = qty_m.group(1).strip() if qty_m else ""

    org_m = re.search(r'(?:Organisation Name|संगठन का नाम)\s*\n\s*([^\n]+)', meta_text, re.IGNORECASE)
    org_name = org_m.group(1).strip() if org_m else ""
    if org_name.lower() in ['n/a', 'na', 'null', 'none']:
        org_name = ""

    dept_m = re.search(r'(?:Department Name| वभाग का नाम)\s*\n\s*([^\n]+)', meta_text, re.IGNORECASE)
    dept_name = dept_m.group(1).strip() if dept_m else ""
    if dept_name.lower() in ['n/a', 'na', 'null', 'none']:
        dept_name = ""

    ministry_m = re.search(r'(?:Ministry/State Name|मं ालय/रा!य का नाम)\s*\n\s*([^\n]+)', meta_text, re.IGNORECASE)
    ministry_name = ministry_m.group(1).strip() if ministry_m else ""

    office_m = re.search(r'(?:Office Name|काया%लय का नाम)\s*\n\s*([^\n]+)', meta_text, re.IGNORECASE)
    office_name = office_m.group(1).strip() if office_m else ""

    auth_name = org_name or dept_name or ministry_name or ""
    auth_dept = dept_name if org_name and dept_name != org_name else (ministry_name if ministry_name != auth_name else "")
    auth_address = office_name or ""

    # Consignee Address lookup
    for pno in range(min(total_pages, 8)):
        t_page = doc[pno].get_text()
        if "Consignee" in t_page or "पता/Address" in t_page:
            addr_m = re.search(r'पता/Address\s*\n\s*(?:[^\n]+\n){0,2}\s*(\d{6},[^\n]+(?:\n[^\n]+){1,3})', t_page)
            if addr_m:
                auth_address = " ".join([l.strip() for l in addr_m.group(1).split("\n") if l.strip()])
                break

    # Item Category extraction from Page 1
    item_category = ""
    lines_p1 = [l.strip() for l in p1_text.split('\n')]
    for i, l in enumerate(lines_p1):
        if 'Item Category' in l or 'व\'तु (ेणी' in l or 'व\'तु' in l:
            cat_lines = []
            for next_l in lines_p1[i+1:]:
                if any(k in next_l for k in ['Turnover', 'Experience', 'Relaxation', 'BOQ', 'GeMARPTS', 'Dated', 'Bid Number', 'पछले', 'मूल', 'उ/ह', 'कुल']):
                    break
                cat_lines.append(next_l)
            item_category = " ".join(cat_lines).strip()
            # Clean trailing (Q...) indicator
            item_category = re.sub(r'\s*\([Qq]\d+\)\s*$', '', item_category).strip()
            break

    # BOQ Title
    boq_title_m = re.search(r'(?:BOQ Title|बीओ8यू शीष%क)\s*\n\s*([^\n]+(?:\n[^\n]+)?)', meta_text, re.IGNORECASE)
    boq_title = " ".join([l.strip() for l in boq_title_m.group(1).split("\n") if l.strip()]) if boq_title_m else ""

    prod_cat_m = re.search(r'(?:Primary product category|5ाथिमक उWपाद \(ेणी)\s*\n\s*([^\n]+)', meta_text, re.IGNORECASE)
    prod_cat = prod_cat_m.group(1).strip() if prod_cat_m else ""

    prod_desc = boq_title or item_category or prod_cat or ""

    # 2. Extract Specifications
    specs = []
    seen = set()

    # Strategy A: GeM Specification Tables
    for pno in range(total_pages):
        page = doc[pno]
        txt = page.get_text()
        if any(k in txt for k in ['Specification Parameter', 'Allowed Values', 'अनुमत मू', 'विश', 'As per GeM']):
            tables = page.find_tables()
            for tab in tables:
                df = tab.extract()
                if not df or len(df) < 2:
                    continue
                header_idx = -1
                for r_i, row in enumerate(df[:3]):
                    row_str = " ".join([str(c or "") for c in row])
                    if any(k in row_str for k in ['Specification', 'Allowed Values', 'अनुमत', 'विश', 'Parameter']):
                        header_idx = r_i
                        break

                if header_idx != -1:
                    for row in df[header_idx + 1:]:
                        if not row or len(row) < 2:
                            continue
                        if len(row) >= 3:
                            param = row[1]
                            val = row[2]
                        else:
                            param = row[0]
                            val = row[1]

                        if param and val:
                            p_clean = " ".join(str(param).split()).strip()
                            v_clean = " ".join(str(val).split()).strip()
                            if any(k in p_clean for k in ['Specification', 'विश', 'ववरण', 'Allowed Values', 'अनुमत', 'S.No', 'H.सं', 'Consignee', 'परे षती', 'BOQ Detail', 'Document']):
                                continue
                            if 'View File' in v_clean or 'द' in v_clean:
                                continue
                            if len(p_clean) > 2 and len(v_clean) > 0:
                                key = p_clean.lower()
                                if key not in seen:
                                    seen.add(key)
                                    specs.append({
                                        'sr': len(specs) + 1,
                                        'parameter': p_clean,
                                        'value': v_clean,
                                        'compliance': 'Yes',
                                        'offered': v_clean,
                                        'deviation': 'No Deviation',
                                        'isAtc': False
                                    })

    # Strategy B: BOQ Items if no standard parameter specs table
    if not specs:
        boq_items = []
        if item_category:
            raw_splits = [x.strip() for x in item_category.split(',') if x.strip()]
            for it in raw_splits:
                if len(it) > 1 and not it.isdigit():
                    boq_items.append(it)

        # Per-page item scan
        page_items = []
        for pno in range(total_pages):
            txt = doc[pno].get_text()
            if 'तकनीक' in txt or 'Technical Specifications' in txt:
                lines = [l.strip() for l in txt.split('\n') if l.strip()]
                for idx, l in enumerate(lines):
                    if 'तकनीक' in l or 'Technical Specifications' in l:
                        for back in range(1, 4):
                            if idx - back >= 0:
                                cand = lines[idx - back]
                                if cand and not any(k in cand for k in ['Days', 'Quantity', 'Address', 'Officer', 'Details', 'Delivery', '402203', 'Ramulu', 'GAIL', 'H.सं', 'S.No', '100%', 'NA', 'जीएसट', 'Bid Document', 'Specifications']):
                                    if len(cand) > 2 and not cand.isdigit():
                                        page_items.append(cand)
                                        break

        distinct_items = []
        for it in (page_items or boq_items):
            key = it.lower().strip()
            if key not in seen:
                seen.add(key)
                distinct_items.append(it)

        for it in distinct_items:
            specs.append({
                'sr': len(specs) + 1,
                'parameter': it,
                'value': 'As per Tender BOQ / Scope of Supply',
                'compliance': 'Yes',
                'offered': f'Complied as per Tender Requirements for {it}',
                'deviation': 'No Deviation',
                'isAtc': False
            })

    # Strategy C: Dynamic compliant row for tender goods/services if still empty
    if not specs:
        desc = prod_desc or f"Tender Goods / Services ({tender_id})"
        q_str = f" (Qty: {total_qty})" if total_qty else ""
        specs.append({
            'sr': 1,
            'parameter': desc,
            'value': f"As per Bid Document / Tender Requirement{q_str}",
            'compliance': 'Yes',
            'offered': f"Fully compliant with all technical specifications and tender requirements for {desc}",
            'deviation': 'No Deviation',
            'isAtc': False
        })

    return {
        'success': True,
        'tenderId': str(tender_id),
        'pdfPath': path_to_use,
        'bidNumber': bid_number,
        'bidDate': bid_date,
        'qty': total_qty,
        'authorityName': auth_name,
        'authorityDept': auth_dept,
        'authorityAddress': auth_address,
        'productDescription': prod_desc or "Specified Goods / Equipment",
        'specs': specs
    }

def main():
    parser = argparse.ArgumentParser(description="Extract technical specifications and metadata from tender PDF")
    parser.add_argument("--tender-id", required=True, help="Tender ID / Directory Name")
    parser.add_argument("--pdf-path", default=None, help="Optional direct path to PDF file")
    args = parser.parse_args()

    result = extract_tender_data_and_specs(args.tender_id, args.pdf_path)
    print(json.dumps(result, ensure_ascii=False, indent=2))

if __name__ == "__main__":
    main()
