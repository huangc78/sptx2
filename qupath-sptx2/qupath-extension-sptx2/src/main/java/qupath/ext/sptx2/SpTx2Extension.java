/*-
 * #%L
 * ST-AnD is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 * 
 * ST-AnD is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 * 
 * You should have received a copy of the GNU General Public License 
 * along with ST-AnD.  If not, see <https://www.gnu.org/licenses/>.
 * #L%
 */

package qupath.ext.sptx2;

import org.controlsfx.control.action.Action;

import javafx.beans.property.BooleanProperty;
import javafx.beans.property.DoubleProperty;
import javafx.beans.property.IntegerProperty;
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.StringProperty;
import javafx.collections.FXCollections;
import javafx.scene.control.Menu;
import qupath.lib.common.Version;
import qupath.lib.gui.ActionTools;
import qupath.lib.gui.QuPathGUI;
import qupath.lib.gui.extensions.GitHubProject;
import qupath.lib.gui.extensions.QuPathExtension;
import qupath.lib.gui.panes.PreferencePane;
import qupath.lib.gui.prefs.PathPrefs;
import qupath.lib.gui.tools.MenuTools;
/**
 * Install SpTx as an extension.
 * 
 * @author Chao Hui Huang
 */
public class SpTx2Extension implements QuPathExtension, GitHubProject {
	
	@SuppressWarnings("unchecked")
	@Override
	public void installExtension(QuPathGUI qupath) {
		final SpTx2Setup sptxOptions = SpTx2Setup.getInstance();
		
        final StringProperty stardistModelLocationPathProp = PathPrefs.createPersistentPreference("stardistModelLocationPath", "");
        sptxOptions.setStardistModelLocationPath(stardistModelLocationPathProp.get());
        stardistModelLocationPathProp.addListener((v,o,n) -> sptxOptions.setStardistModelLocationPath(n));
        final PreferencePane stardistPrefs = QuPathGUI.getInstance().getPreferencePane();
        stardistPrefs.addPropertyPreference(stardistModelLocationPathProp, String.class, "Stardist Model directory", "Stardist",
                "Enter the directory where the stardist models are located.");
        
//        final StringProperty pathDetObjImgAcqDistDirProp = PathPrefs.createPersistentPreference("pathDetObjImgAcqDistDir", "");
//        sptxOptions.setPathDetObjImgAcqDistDir(pathDetObjImgAcqDistDirProp.get());
//        pathDetObjImgAcqDistDirProp.addListener((v,o,n) -> sptxOptions.setPathDetObjImgAcqDistDir(n));
//
//        final StringProperty pathDetObjImgAcqPrefixProp = PathPrefs.createPersistentPreference("pathDetObjImgAcqPrefix", "");
//        sptxOptions.setPathDetObjImgAcqPrefix(pathDetObjImgAcqPrefixProp.get());
//        pathDetObjImgAcqPrefixProp.addListener((v,o,n) -> sptxOptions.setPathDetObjImgAcqPrefix(n));
//        
//        
//        final DoubleProperty pathDetObjImgAcqMPPProp = PathPrefs.createPersistentPreference("pathDetObjImgAcqMPP", 0.124);
//        sptxOptions.setPathDetObjImgAcqMPP(pathDetObjImgAcqMPPProp.get());
//        pathDetObjImgAcqMPPProp.addListener((v,o,n) -> sptxOptions.setPathDetObjImgAcqMPP(n));
//        
//        final BooleanProperty pathDetObjImgAcqDontRescalingProp = PathPrefs.createPersistentPreference("pathDetObjImgAcqDontRescaling", true);
//        sptxOptions.setPathDetObjImgAcqDontRescaling(pathDetObjImgAcqDontRescalingProp.get());
//        pathDetObjImgAcqDontRescalingProp.addListener((v,o,n) -> sptxOptions.setPathDetObjImgAcqDontRescaling(n));
//        
//        final IntegerProperty pathDetObjImgAcqSamplingSizeProp = PathPrefs.createPersistentPreference("pathDetObjImgAcqSamplingSize", 36);
//        sptxOptions.setPathDetObjImgAcqSamplingSize(pathDetObjImgAcqSamplingSizeProp.get());
//        pathDetObjImgAcqSamplingSizeProp.addListener((v,o,n) -> sptxOptions.setPathDetObjImgAcqSamplingSize(n.intValue()));
//        
//        final IntegerProperty pathDetObjImgAcqSamplingNumProp = PathPrefs.createPersistentPreference("pathDetObjImgAcqSamplingNum", -1);
//        sptxOptions.setPathDetObjImgAcqSamplingNum(pathDetObjImgAcqSamplingNumProp.get());
//        pathDetObjImgAcqSamplingNumProp.addListener((v,o,n) -> sptxOptions.setPathDetObjImgAcqSamplingNum(n.intValue()));     
//        
//        final StringProperty pathDetObjImgAcqSamplingFmtProp = PathPrefs.createPersistentPreference("pathDetObjImgAcqSamplingFmt", "");
//        sptxOptions.setPathDetObjImgAcqSamplingFmt(pathDetObjImgAcqSamplingFmtProp.get());
//        pathDetObjImgAcqSamplingFmtProp.addListener((v,o,n) -> sptxOptions.setPathDetObjImgAcqSamplingFmt(n));
//        
        
		Menu menu = qupath.getMenu("Extensions>SpTx Analysis Toolbox", true);
		
//		MenuTools.addMenuItems(
//				menu,
//				ActionTools.createAction(new STAnDConfiguration(qupath), "Configuration")
//				);
		
		
				
//		MenuTools.addMenuItems(
//				menu,
//				null,
//				ActionTools.createAction(new SpTxDataSetPreparation(qupath), "Training Data Preparation")
//				);
		
		MenuTools.addMenuItems(
				menu,
				null,
				qupath.createPluginAction("StarDist-based Nucleus Detection", StarDistCellNucleusDetection.class, null)
				);		
		
		
		
		
		// final Action actionSpTxVisiumAnnotationLoader = qupath.createImageDataAction(imageData -> new SpTxVisiumAnnotation(qupath));
		// actionSpTxVisiumAnnotationLoader.setText("Import Visium Annotation");
		
		// MenuTools.addMenuItems(
		// 		menu,
		// 		actionSpTxVisiumAnnotationLoader
		// 		);		

		
		MenuTools.addMenuItems(
				menu,
				qupath.createPluginAction("Import ST Annotation", STAnnotation.class, null)
				);
		
		MenuTools.addMenuItems(
				menu,
				qupath.createPluginAction("Import 10x Visium Annotation", VisiumAnnotation.class, null)
				);
		
		MenuTools.addMenuItems(
				menu,
				qupath.createPluginAction("Import 10x Xenium Annotation", XeniumAnnotation.class, null)
				);
		
		MenuTools.addMenuItems(
				menu,
				qupath.createPluginAction("Import NanoString CosMX Annotation", CosmxAnnotation.class, null)
				);
		
		MenuTools.addMenuItems(
				menu,
				qupath.createPluginAction("Spatial Analysis", SpatialAnalysis.class, null)
				);

		MenuTools.addMenuItems(
				menu,
				qupath.createPluginAction("Cell-Cell Interaction Analysis", CellCellInteractionAnalysis.class, null)
				);
		
		
		MenuTools.addMenuItems(
				menu,
				qupath.createPluginAction("Pseudo Spot Generation", PseudoVisiumSpotGeneration.class, null)
				);
		
		MenuTools.addMenuItems(
				menu,
				qupath.createPluginAction("Export Detection Object Images", PathDetectionObjectImageAcquisition.class, null)
				);
		
		MenuTools.addMenuItems(
				menu,
				qupath.createPluginAction("Xenium Pixel Size Calibration", XeniumPixelSizeCalibration.class, null)
				);
		
//		MenuTools.addMenuItems(
//				menu,
//				qupath.createPluginAction("Single Cell Gene Expression Prediction", STAnDSingleCellGeneExpressionPrediction.class, null)
//				);
		
//		MenuTools.addMenuItems(
//				menu,
//				qupath.createPluginAction("Single Cell Subclass Prediction", STGAINSingleCellSubclassPrediction.class, null)
//				);
		
//		final Action actionSpTxSingleCellGeneExpressionPredictionEvaluator = qupath.createImageDataAction(imageData -> new STAnDGeneExpressionEvaluation(qupath));
//		actionSpTxSingleCellGeneExpressionPredictionEvaluator.setText("Single Cell Gene Expression Evaluation");
//		
//		MenuTools.addMenuItems(
//				menu,
//				actionSpTxSingleCellGeneExpressionPredictionEvaluator
//				);
		
//		MenuTools.addMenuItems(
//				menu,
//				qupath.createPluginAction("Single Cell PhenoGraph", STAnDSingleCellGeneExpressionPhenoGraph.class, null)
//				);	
		
		
//		MenuTools.addMenuItems(
//				menu,
//				qupath.createPluginAction("Cell Phenotype Analysis", SpTxCellPhenotypeAnalysis.class, null)
//				);
//		
//		MenuTools.addMenuItems(
//				menu,
//				qupath.createPluginAction("Cell-Cell Interaction Analysis", SpTxCellInteractionAnalysis.class, null)
//				);		
	}

	@Override
	public String getName() {
		return "SpTx Extension";
	}

	@Override
	public String getDescription() {
		return "Run SpTx Extension.\n"
				+ "See the extension repository for citation information.";
	}
	
	@Override
	public Version getQuPathVersion() {
		return Version.parse("0.3.0-rc2");
	}

	@Override
	public GitHubRepo getRepository() {
		return GitHubRepo.create(getName(), "qupath", "qupath-extension-sptx");
	}

}
