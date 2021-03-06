package org.visallo.themoviedb.download;

import org.visallo.core.util.VisalloLogger;
import org.visallo.core.util.VisalloLoggerFactory;
import org.json.JSONObject;

public class ProductionCompanyDownloadWorkItem extends WorkItem {
    private static final VisalloLogger LOGGER = VisalloLoggerFactory.getLogger(ProductionCompanyDownloadWorkItem.class);
    private final int productionCompanyId;

    public ProductionCompanyDownloadWorkItem(int productionCompanyId) {
        this.productionCompanyId = productionCompanyId;
    }

    @Override
    public boolean process(TheMovieDbDownload theMovieDbDownload) throws Exception {
        if (theMovieDbDownload.hasProductionCompanyInCache(productionCompanyId)) {
            return false;
        }
        LOGGER.debug("Downloading production company: %d", productionCompanyId);
        JSONObject productionCompanyJson = theMovieDbDownload.getTheMovieDb().getProductionCompanyInfo(productionCompanyId);
        theMovieDbDownload.writeProductionCompany(productionCompanyId, productionCompanyJson);
        return true;
    }
}
