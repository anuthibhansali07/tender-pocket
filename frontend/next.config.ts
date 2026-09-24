import type { NextConfig } from "next";

const nextConfig: NextConfig = {
  serverExternalPackages: ["pdfkit", "pdfkit-table"],
  images: {
    unoptimized: true
  },
  async rewrites() {
    return [
      {
        source: '/documents/:id/:filename*',
        destination: '/api/tenders/:id/documents/:filename*',
      },
    ];
  },
};

export default nextConfig;
