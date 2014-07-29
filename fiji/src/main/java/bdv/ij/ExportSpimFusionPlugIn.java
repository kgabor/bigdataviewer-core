package bdv.ij;

import fiji.plugin.Bead_Registration;
import fiji.plugin.Multi_View_Fusion;
import fiji.util.gui.GenericDialogPlus;
import ij.IJ;
import ij.ImagePlus;
import ij.gui.DialogListener;
import ij.gui.GenericDialog;
import ij.plugin.PlugIn;

import java.awt.AWTEvent;
import java.awt.Checkbox;
import java.awt.TextField;
import java.awt.event.ItemEvent;
import java.awt.event.ItemListener;
import java.awt.event.TextEvent;
import java.awt.event.TextListener;
import java.io.BufferedReader;
import java.io.File;
import java.io.FilenameFilter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;

import mpicbg.spim.data.SpimDataException;
import mpicbg.spim.data.generic.sequence.AbstractSequenceDescription;
import mpicbg.spim.data.generic.sequence.BasicViewSetup;
import mpicbg.spim.data.registration.ViewRegistrations;
import mpicbg.spim.data.sequence.FinalVoxelDimensions;
import mpicbg.spim.data.sequence.TimePoint;
import mpicbg.spim.data.sequence.VoxelDimensions;
import mpicbg.spim.io.ConfigurationParserException;
import mpicbg.spim.io.IOFunctions;
import mpicbg.spim.io.SPIMConfiguration;
import mpicbg.spim.io.TextFileAccess;
import net.imglib2.Dimensions;
import net.imglib2.FinalDimensions;
import net.imglib2.Pair;
import net.imglib2.realtransform.AffineTransform3D;
import net.imglib2.util.ValuePair;
import spimopener.SPIMExperiment;
import bdv.export.ExportMipmapInfo;
import bdv.export.ProgressWriter;
import bdv.export.ProposeMipmaps;
import bdv.export.SubTaskProgressWriter;
import bdv.export.WriteSequenceToHdf5;
import bdv.ij.export.FusionResult;
import bdv.ij.export.SetupAggregator;
import bdv.ij.export.SpimRegistrationSequence;
import bdv.ij.util.PluginHelper;
import bdv.ij.util.ProgressWriterIJ;
import bdv.img.hdf5.Hdf5ImageLoader;
import bdv.img.hdf5.MipmapInfo;
import bdv.img.hdf5.Partition;
import bdv.img.hdf5.Util;
import bdv.spimdata.SequenceDescriptionMinimal;
import bdv.spimdata.SpimDataMinimal;
import bdv.spimdata.XmlIoSpimDataMinimal;

public class ExportSpimFusionPlugIn implements PlugIn
{
	static double minValueStatic = 0;

	static double maxValueStatic = 65535;

	static boolean lastSetMipmapManual = false;

	static String lastSubsampling = "{1,1,1}, {2,2,2}, {4,4,4}";

	static String lastChunkSizes = "{32,32,32}, {16,16,16}, {8,8,8}";

	static String autoSubsampling = "{1,1,1}";

	static String autoChunkSizes = "{16,16,16}";

	@Override
	public void run( final String arg0 )
	{
		final Parameters params = getParameters();

		// cancelled
		if ( params == null )
			return;

		try
		{
			IJ.log( "starting export..." );
			if ( params.appendToExistingFile && params.seqFile.exists() )
				appendToExistingFile( params );
			else
				saveAsNewFile( params );
			IJ.showProgress( 1 );
			IJ.log( "done" );
		}
		catch ( final Exception e )
		{
			e.printStackTrace();
		}
	}

	public static void appendToExistingFile( final Parameters params ) throws SpimDataException, IOException
	{
		final ProgressWriter progress = new ProgressWriterIJ();
		final XmlIoSpimDataMinimal spimDataIo = new XmlIoSpimDataMinimal();

		final SpimRegistrationSequence spimseq = new SpimRegistrationSequence( params.conf );
		final Map< Integer, AffineTransform3D > fusionTransforms = spimseq.getFusionTransforms( params.cropOffsetX, params.cropOffsetY, params.cropOffsetZ, params.scale );
		final FusionResult fusionResult = FusionResult.create( spimseq, params.fusionDirectory, params.filenamePattern, params.numSlices, params.sliceValueMin, params.sliceValueMax, fusionTransforms );

		// aggregate the ViewSetups
		final SetupAggregator aggregator = new SetupAggregator();

		// first add the setups from the existing dataset
		final File existingDatasetXmlFile = params.seqFile;
		final File baseDirectory = existingDatasetXmlFile.getParentFile();
		final SpimDataMinimal existingSpimData = spimDataIo.load( existingDatasetXmlFile.getAbsolutePath() );

		final SequenceDescriptionMinimal existingSequence = existingSpimData.getSequenceDescription();
		final ViewRegistrations existingRegistrations = existingSpimData.getViewRegistrations();
		final Hdf5ImageLoader hdf5Loader = ( Hdf5ImageLoader ) existingSequence.getImgLoader();

		final Map< Integer, Integer > setupIdAggregatorToExisting = new HashMap< Integer, Integer >();
		for ( final BasicViewSetup existingSetup : existingSequence.getViewSetupsOrdered() )
		{
			final MipmapInfo info = hdf5Loader.getMipmapInfo( existingSetup.getId() );
			final int id = aggregator.add( existingSetup, existingSequence, existingRegistrations, Util.castToInts( info.getResolutions() ), info.getSubdivisions() );
			setupIdAggregatorToExisting.put( id, existingSetup.getId() );
		}

		// now add a new setup from the fusion result
		final AbstractSequenceDescription< ?, ?, ? > fusionSeq = fusionResult.getSequenceDescription();
		final ViewRegistrations fusionReg = fusionResult.getViewRegistrations();
		final BasicViewSetup fusionSetup = fusionSeq.getViewSetupsOrdered().get( 0 );
		final int fusionSetupWrapperId = aggregator.add( fusionSetup, fusionSeq, fusionReg, params.resolutions, params.subdivisions );

		// maps every timepoint id to itself, needed for partitions
		final Map< Integer, Integer > timepointIdentityMap = new HashMap< Integer, Integer >();
		for ( final TimePoint tp : existingSequence.getTimePoints().getTimePointsOrdered() )
			timepointIdentityMap.put( tp.getId(), tp.getId() );

		// setup the partitions
		final ArrayList< Partition > partitions = new ArrayList< Partition >( hdf5Loader.getPartitions() );
		final boolean notYetPartitioned = partitions.isEmpty();
		if ( notYetPartitioned )
			// add a new partition for the existing stuff
			partitions.add( new Partition( hdf5Loader.getHdf5File().getAbsolutePath(), timepointIdentityMap, setupIdAggregatorToExisting ) );

		// add partition for the fused data
		final ArrayList< Partition > newPartitions = new ArrayList< Partition >();
		final String newPartitionPath = PluginHelper.createNewPartitionFile( existingDatasetXmlFile ).getAbsolutePath();
		final HashMap< Integer, Integer > setupIdSequenceToPartition = new HashMap< Integer, Integer >();
		setupIdSequenceToPartition.put( fusionSetupWrapperId, fusionSetupWrapperId );
		newPartitions.add( new Partition( newPartitionPath, timepointIdentityMap, setupIdSequenceToPartition ) );
		partitions.addAll( newPartitions );

		final SpimDataMinimal aggregateSpimData = aggregator.createSpimData( baseDirectory );
		final SequenceDescriptionMinimal aggregateSeq = aggregateSpimData.getSequenceDescription();
		final Map< Integer, ExportMipmapInfo > aggregateMipmapInfos = aggregator.getPerSetupMipmapInfo();

		double complete = 0.05;
		progress.setProgress( complete );

		// write new data partitions
		final double completionStep = ( 0.95 - complete ) / newPartitions.size();
		for ( final Partition partition : newPartitions )
		{
			final SubTaskProgressWriter subtaskProgress = new SubTaskProgressWriter( progress, complete, complete + completionStep );
			WriteSequenceToHdf5.writeHdf5PartitionFile( aggregateSeq, aggregateMipmapInfos, partition, subtaskProgress );
			complete += completionStep;
		}

		// (re-)write hdf5 link file
		final File newHdf5PartitionLinkFile = PluginHelper.createNewPartitionFile( existingDatasetXmlFile );
		WriteSequenceToHdf5.writeHdf5PartitionLinkFile( aggregateSeq, aggregateMipmapInfos, partitions, newHdf5PartitionLinkFile );
		progress.setProgress( 1 );

		// re-write xml file
		spimDataIo.save(
				new SpimDataMinimal( aggregateSpimData, new Hdf5ImageLoader( newHdf5PartitionLinkFile, partitions, null, false ) ),
				params.seqFile.getAbsolutePath() );
	}

	public static void saveAsNewFile( final Parameters params ) throws SpimDataException
	{
		final ProgressWriter progress = new ProgressWriterIJ();
		final XmlIoSpimDataMinimal spimDataIo = new XmlIoSpimDataMinimal();

		final SpimRegistrationSequence spimseq = new SpimRegistrationSequence( params.conf );
		final Map< Integer, AffineTransform3D > fusionTransforms = spimseq.getFusionTransforms( params.cropOffsetX, params.cropOffsetY, params.cropOffsetZ, params.scale );
		final FusionResult fusionResult = FusionResult.create( spimseq, params.fusionDirectory, params.filenamePattern, params.numSlices, params.sliceValueMin, params.sliceValueMax, fusionTransforms );

		// aggregate the ViewSetups
		final SetupAggregator aggregator = new SetupAggregator();

		// add the setups from the fusion result
		aggregator.addSetups( fusionResult, params.resolutions, params.subdivisions );

		final File baseDirectory = params.seqFile.getParentFile();
		final SpimDataMinimal aggregateSpimData = aggregator.createSpimData( baseDirectory );
		final SequenceDescriptionMinimal aggregateSeq = aggregateSpimData.getSequenceDescription();
		final Map< Integer, ExportMipmapInfo > aggregateMipmapInfos = aggregator.getPerSetupMipmapInfo();

		// write single hdf5 file
		WriteSequenceToHdf5.writeHdf5File( aggregateSeq, aggregateMipmapInfos, params.hdf5File, new SubTaskProgressWriter( progress, 0, 0.95 ) );

		// re-write xml file
		spimDataIo.save(
				new SpimDataMinimal( aggregateSpimData, new Hdf5ImageLoader( params.hdf5File, null, null, false ) ),
				params.seqFile.getAbsolutePath() );
	}

	public static String allChannels = "0, 1";

	public static class Parameters
	{
		final SPIMConfiguration conf;
		final int[][] resolutions;
		final int[][] subdivisions;
		final int cropOffsetX;
		final int cropOffsetY;
		final int cropOffsetZ;
		final int scale;
		final String fusionDirectory;
		final String filenamePattern;
		final int numSlices;
		final double sliceValueMin;
		final double sliceValueMax;
		final File seqFile;
		final File hdf5File;
		final boolean appendToExistingFile;

		public Parameters( final SPIMConfiguration conf, final int[][] resolutions, final int[][] subdivisions,
				final int cropOffsetX, final int cropOffsetY, final int cropOffsetZ, final int scale,
				final String fusionDirectory, final String filenamePattern, final int numSlices,
				final double sliceValueMin, final double sliceValueMax,
				final File seqFile, final File hdf5File, final boolean appendToExistingFile )
		{
			this.conf = conf;
			this.resolutions = resolutions;
			this.subdivisions = subdivisions;
			this.cropOffsetX = cropOffsetX;
			this.cropOffsetY = cropOffsetY;
			this.cropOffsetZ = cropOffsetZ;
			this.scale = scale;
			this.fusionDirectory = fusionDirectory;
			this.filenamePattern = filenamePattern;
			this.numSlices = numSlices;
			this.sliceValueMin = sliceValueMin;
			this.sliceValueMax = sliceValueMax;
			this.seqFile = seqFile;
			this.hdf5File = hdf5File;
			this.appendToExistingFile = appendToExistingFile;
		}
	}

	protected Parameters getParameters()
	{
		final GenericDialog gd0 = new GenericDialogPlus( "Export for BigDataViewer" );
		gd0.addChoice( "Select_channel type", ExportSpimSequencePlugIn.fusionType, ExportSpimSequencePlugIn.fusionType[ Multi_View_Fusion.defaultFusionType ] );
		gd0.showDialog();
		if ( gd0.wasCanceled() )
			return null;
		final int channelChoice = gd0.getNextChoiceIndex();
		Multi_View_Fusion.defaultFusionType = channelChoice;
		final boolean multichannel = channelChoice == 1;

		final GenericDialogPlus gd = new GenericDialogPlus( "Export for BigDataViewer" );

		gd.addDirectoryOrFileField( "SPIM_data_directory", Bead_Registration.spimDataDirectory );
		final TextField tfSpimDataDirectory = (TextField) gd.getStringFields().lastElement();
		gd.addStringField( "Pattern_of_SPIM files", Bead_Registration.fileNamePattern, 25 );
		final TextField tfFilePattern = (TextField) gd.getStringFields().lastElement();
		gd.addStringField( "Timepoints_to_process", Bead_Registration.timepoints );
		final TextField tfTimepoints = (TextField) gd.getStringFields().lastElement();
		gd.addStringField( "Angles to process", Bead_Registration.angles );
		final TextField tfAngles = (TextField) gd.getStringFields().lastElement();

		final TextField tfChannels;
		if ( multichannel )
		{
			gd.addStringField( "Channels to process", allChannels );
			tfChannels = (TextField) gd.getStringFields().lastElement();
		}
		else
			tfChannels = null;

//		gd.addMessage("");
//		gd.addMessage("This Plugin is developed by Tobias Pietzsch (pietzsch@mpi-cbg.de)\n");
//		Bead_Registration.addHyperLinkListener( (MultiLineLabel) gd.getMessage(), "mailto:pietzsch@mpi-cbg.de");

		gd.addDialogListener( new DialogListener()
		{
			@Override
			public boolean dialogItemChanged( final GenericDialog dialog, final AWTEvent e )
			{
				if ( e instanceof TextEvent && e.getID() == TextEvent.TEXT_VALUE_CHANGED && e.getSource() == tfSpimDataDirectory )
				{
					final TextField tf = ( TextField ) e.getSource();
					final String spimDataDirectory = tf.getText();
					final File f = new File( spimDataDirectory );
					if ( f.exists() && f.isFile() && f.getName().endsWith( ".xml" ) )
					{
						final SPIMExperiment exp = new SPIMExperiment( f.getAbsolutePath() );

						// disable file pattern field
						tfFilePattern.setEnabled( false );

						// set timepoint string
						String expTimepoints = "";
						if ( exp.timepointStart == exp.timepointEnd )
							expTimepoints = "" + exp.timepointStart;
						else
							expTimepoints = "" + exp.timepointStart + "-" + exp.timepointEnd;
						tfTimepoints.setText( expTimepoints );

						// set angles string
						String expAngles = "";
						for ( final String angle : exp.angles )
						{
							final int a = Integer.parseInt( angle.substring( 1, angle.length() ) );
							if ( !expAngles.equals( "" ) )
								expAngles += ",";
							expAngles += a;
						}
						tfAngles.setText( expAngles );

						if ( multichannel )
						{
							// set channels string
							String expChannels = "";
							for ( final String channel : exp.channels )
							{
								final int c = Integer.parseInt( channel.substring( 1, channel.length() ) );
								if ( !expChannels.equals( "" ) )
									expChannels += ",";
								expChannels += c;
							}
							tfChannels.setText( expChannels );
						}
					}
					else
					{
						// enable file pattern field
						tfFilePattern.setEnabled( true );
					}
				}
				return true;
			}
		} );

		File f = new File( tfSpimDataDirectory.getText() );
		if ( f.exists() && f.isFile() && f.getName().endsWith( ".xml" ) )
		{
			// disable file pattern field
			tfFilePattern.setEnabled( false );
			if ( multichannel )
			{
				// set channels string
				final SPIMExperiment exp = new SPIMExperiment( f.getAbsolutePath() );
				String expChannels = "";
				for ( final String channel : exp.channels )
				{
					final int c = Integer.parseInt( channel.substring( 1, channel.length() ) );
					if ( !expChannels.equals( "" ) )
						expChannels += ",";
					expChannels += c;
				}
				tfChannels.setText( expChannels );
			}
		}
		gd.showDialog();

		if ( gd.wasCanceled() )
			return null;

		Bead_Registration.spimDataDirectory = gd.getNextString();
		Bead_Registration.fileNamePattern = gd.getNextString();
		Bead_Registration.timepoints = gd.getNextString();
		Bead_Registration.angles = gd.getNextString();

		int numChannels = 0;
		ArrayList<Integer> channels;

		// verify this part
		if ( multichannel )
		{
			allChannels = gd.getNextString();

			try
			{
				channels = SPIMConfiguration.parseIntegerString( allChannels );
				numChannels = channels.size();
			}
			catch (final ConfigurationParserException e)
			{
				IOFunctions.printErr( "Cannot understand/parse the channels: " + allChannels );
				return null;
			}

			if ( numChannels < 1 )
			{
				IOFunctions.printErr( "There are no channels given: " + allChannels );
				return null;
			}
		}
		else
		{
			numChannels = 1;
			channels = new ArrayList<Integer>();
			channels.add( 0 );
		}

		// create the configuration object
		final SPIMConfiguration conf = new SPIMConfiguration();

		conf.timepointPattern = Bead_Registration.timepoints;
		conf.anglePattern = Bead_Registration.angles;
		if ( multichannel )
		{
			conf.channelPattern = allChannels;
			conf.channelsToRegister = allChannels;
			conf.channelsToFuse = allChannels;
		}
		else
		{
			conf.channelPattern = "";
			conf.channelsToRegister = "";
			conf.channelsToFuse = "";
		}
		conf.inputFilePattern = Bead_Registration.fileNamePattern;

		f = new File( Bead_Registration.spimDataDirectory );
		if ( f.exists() && f.isFile() && f.getName().endsWith( ".xml" ) )
		{
			conf.spimExperiment = new SPIMExperiment( f.getAbsolutePath() );
			conf.inputdirectory = f.getAbsolutePath().substring( 0, f.getAbsolutePath().length() - 4 );
			System.out.println( "inputdirectory : " + conf.inputdirectory );
		}
		else
		{
			conf.inputdirectory = Bead_Registration.spimDataDirectory;
		}

		conf.fuseOnly = true; // this is to avoid an exception in the multi-channel case

		// get filenames and so on...
		if ( ! ExportSpimSequencePlugIn.init( conf ) )
			return null;

		// test which registration files are there for each channel
		// file = new File[ timepoints.length ][ channels.length ][ angles.length ];
		final ArrayList<ArrayList<Integer>> timepoints = new ArrayList<ArrayList<Integer>>();
		int numChoices = 0;
		conf.zStretching = -1;

		for ( int c = 0; c < channels.size(); ++c )
		{
			timepoints.add( new ArrayList<Integer>() );

			final String name = conf.file[ 0 ][ c ][ 0 ][ 0 ].getName();
			final File regDir = new File( conf.registrationFiledirectory );

			IOFunctions.println( "name: " + name );
			IOFunctions.println( "dir: " + regDir.getAbsolutePath() );

			if ( !regDir.isDirectory() )
			{
				IOFunctions.println( conf.registrationFiledirectory + " is not a directory. " );
				return null;
			}

			final String entries[] = regDir.list( new FilenameFilter() {
				@Override
				public boolean accept(final File directory, final String filename)
				{
					if ( filename.contains( name ) && filename.contains( ".registration" ) )
						return true;
					else
						return false;
				}
			});

			for ( final String e : entries )
				IOFunctions.println( e );

			for ( final String s : entries )
			{
				if ( s.endsWith( ".registration" ) )
				{
					if ( !timepoints.get( c ).contains( -1 ) )
					{
						timepoints.get( c ).add( -1 );
						numChoices++;
					}
				}
				else
				{
					final int timepoint = Integer.parseInt( s.substring( s.indexOf( ".registration.to_" ) + 17, s.length() ) );

					if ( !timepoints.get( c ).contains( timepoint ) )
					{
						timepoints.get( c ).add( timepoint );
						numChoices++;
					}
				}

				if ( conf.zStretching < 0 )
				{
					conf.zStretching = loadZStretching( conf.registrationFiledirectory + s );
					conf.overrideImageZStretching = true;
					IOFunctions.println( "Z-stretching = " + conf.zStretching );
				}
			}
		}

		if ( numChoices == 0 )
		{
			IOFunctions.println( "No registration files available." );
			return null;
		}

		for ( int c = 0; c < channels.size(); ++c )
			for ( final int i : timepoints.get( c ) )
				IOFunctions.println( c + ": " + i );

		final GenericDialogPlus gd2 = new GenericDialogPlus( "Export for BigDataViewer" );

		// build up choices
		final String[] choices = new String[ numChoices ];
		final int[] suggest = new int[ channels.size() ];

		int firstSuggestion = -1;
		int index = 0;
		for ( int c = 0; c < channels.size(); ++c )
		{
			final ArrayList<Integer> tps = timepoints.get( c );

			// no suggestion yet
			suggest[ c ] = -1;
			for ( int i = 0; i < tps.size(); ++i )
			{
				if ( tps.get( i ) == -1 )
					choices[ index ] = "Individual registration of channel " + channels.get( c );
				else
					choices[ index ] = "Time-point registration (reference=" + tps.get( i ) + ") of channel " + channels.get( c );

				if ( suggest[ c ] < 1 )
				{
					suggest[ c ] = index;
					if ( firstSuggestion < 1 )
						firstSuggestion = index;
				}

				index++;
			}
		}

		for ( int c = 0; c < channels.size(); ++c )
			if ( suggest[ c ] == -1 )
				suggest[ c ] = firstSuggestion;

		for ( int c = 0; c < channels.size(); ++c )
			gd2.addChoice( "Registration for channel " + channels.get( c ), choices, choices[ suggest[ c ] ]);

		gd2.addMessage( "" );
		gd2.addDirectoryField( "Fusion Output Directory", conf.outputdirectory, 25 );
		final TextField tfDirectory = (TextField) gd2.getStringFields().lastElement();

		gd2.addMessage( "" );
		gd2.addMessage( "Enter the crop values you used to create the fused data:" );
		gd2.addNumericField( "Downsample_output image n-times", Multi_View_Fusion.outputImageScalingStatic, 0 );
		gd2.addNumericField( "Crop_output_image_offset_x", Multi_View_Fusion.cropOffsetXStatic, 0 );
		gd2.addNumericField( "Crop_output_image_offset_y", Multi_View_Fusion.cropOffsetYStatic, 0 );
		gd2.addNumericField( "Crop_output_image_offset_z", Multi_View_Fusion.cropOffsetZStatic, 0 );

		gd2.addMessage( "" );
		gd2.addNumericField( "min brightness value", minValueStatic, 0 );
		gd2.addNumericField( "max brightness value", maxValueStatic, 0 );

		gd2.addMessage( "" );
		gd2.addCheckbox( "manual mipmap setup", lastSetMipmapManual );
		final Checkbox cManualMipmap = ( Checkbox ) gd2.getCheckboxes().lastElement();
		gd2.addStringField( "Subsampling factors", lastSubsampling, 25 );
		final TextField tfSubsampling = ( TextField ) gd2.getStringFields().lastElement();
		gd2.addStringField( "Hdf5 chunk sizes", lastChunkSizes, 25 );
		final TextField tfChunkSizes = ( TextField ) gd2.getStringFields().lastElement();

		gd2.addMessage( "" );
		PluginHelper.addSaveAsFileField( gd2, "Export path", conf.inputdirectory + "export.xml", 25 );
		gd2.addCheckbox( "add to existing file", true );

//		gd.addMessage("");
//		gd.addMessage("This Plugin is developed by Tobias Pietzsch (pietzsch@mpi-cbg.de)\n");
//		Bead_Registration.addHyperLinkListener( (MultiLineLabel) gd.getMessage(), "mailto:pietzsch@mpi-cbg.de");

		tfDirectory.addTextListener( new TextListener()
		{
			@Override
			public void textValueChanged( final TextEvent t )
			{
				if ( t.getID() == TextEvent.TEXT_VALUE_CHANGED )
				{
					final String fusionDirectory = tfDirectory.getText();
					if ( updateProposedMipmaps( fusionDirectory, conf ) )
					{
						final boolean useManual = cManualMipmap.getState();
						tfSubsampling.setEnabled( useManual );
						tfChunkSizes.setEnabled( useManual );
						if ( !useManual )
						{
							tfSubsampling.setText( autoSubsampling );
							tfChunkSizes.setText( autoChunkSizes );
						}
					}
				}
			}
		});

		cManualMipmap.addItemListener( new ItemListener()
		{
			@Override
			public void itemStateChanged( final ItemEvent arg0 )
			{
				final boolean useManual = cManualMipmap.getState();
				tfSubsampling.setEnabled( useManual );
				tfChunkSizes.setEnabled( useManual );
				if ( !useManual )
				{
					tfSubsampling.setText( autoSubsampling );
					tfChunkSizes.setText( autoChunkSizes );
				}
			}
		} );

		updateProposedMipmaps( tfDirectory.getText(), conf );
		tfSubsampling.setEnabled( lastSetMipmapManual );
		tfChunkSizes.setEnabled( lastSetMipmapManual );
		if ( !lastSetMipmapManual )
		{
			tfSubsampling.setText( autoSubsampling );
			tfChunkSizes.setText( autoChunkSizes );
		}

		gd2.showDialog();

		if ( gd2.wasCanceled() )
			return null;

		// which channel uses which registration file from which channel to be fused
		final int[][] registrationAssignment = new int[ channels.size() ][ 2 ];

		for ( int c = 0; c < channels.size(); ++c )
		{
			final int choice = gd2.getNextChoiceIndex();

			index = 0;
			for ( int c2 = 0; c2 < channels.size(); ++c2 )
			{
				final ArrayList<Integer> tps = timepoints.get( c2 );
				for ( int i = 0; i < tps.size(); ++i )
				{
					if ( index == choice )
					{
						registrationAssignment[ c ][ 0 ] = tps.get( i );
						registrationAssignment[ c ][ 1 ] = c2;
					}
					index++;
				}
			}
		}

		// test consistency
		final int tp = registrationAssignment[ 0 ][ 0 ];

		for ( int c = 1; c < channels.size(); ++c )
		{
			if ( tp != registrationAssignment[ c ][ 0 ] )
			{
				IOFunctions.println( "Inconsistent choice of reference timeseries, only same reference timepoints or individual registration are allowed.");
				return null;
			}
		}

		// save from which channel to load registration
		conf.registrationAssignmentForFusion = new int[ channels.size() ];
		for ( int c = 0; c < channels.size(); ++c )
		{
			IOFunctions.println( "channel " + c + " takes it from channel " + registrationAssignment[ c ][ 1 ] );
			conf.registrationAssignmentForFusion[ c ] = registrationAssignment[ c ][ 1 ];
		}

		conf.timeLapseRegistration = ( tp >= 0 );
		conf.referenceTimePoint = tp;

		IOFunctions.println( "tp " + tp );

		// get fusion directory
		final String fusionDirectory = gd2.getNextString();

		// detect filename pattern
		final Pair< String, Integer > pair = detectPatternAndNumSlices( new File ( fusionDirectory ), conf.timepoints[0] );
		if ( pair == null )
		{
			IOFunctions.println( "Couldn't detect filename pattern" );
			return null;
		}
		final String filenamePattern = pair.getA();
		final int numSlices = pair.getB();

		// get crop settings
		Multi_View_Fusion.outputImageScalingStatic = (int)Math.round( gd2.getNextNumber() );
		Multi_View_Fusion.cropOffsetXStatic = (int)Math.round( gd2.getNextNumber() );
		Multi_View_Fusion.cropOffsetYStatic = (int)Math.round( gd2.getNextNumber() );
		Multi_View_Fusion.cropOffsetZStatic = (int)Math.round( gd2.getNextNumber() );

		// get min/max brightness
		minValueStatic = gd2.getNextNumber();
		maxValueStatic = gd2.getNextNumber();

		// parse mipmap resolutions and cell sizes
		lastSubsampling = gd2.getNextString();
		lastChunkSizes = gd2.getNextString();
		final int[][] resolutions = PluginHelper.parseResolutionsString( lastSubsampling );
		final int[][] subdivisions = PluginHelper.parseResolutionsString( lastChunkSizes );
		if ( resolutions.length == 0 )
		{
			IOFunctions.println( "Cannot parse subsampling factors " + lastSubsampling );
			return null;
		}
		if ( subdivisions.length == 0 )
		{
			IOFunctions.println( "Cannot parse hdf5 chunk sizes " + lastChunkSizes );
			return null;
		}
		else if ( resolutions.length != subdivisions.length )
		{
			IOFunctions.println( "subsampling factors and hdf5 chunk sizes must have the same number of elements" );
			return null;
		}

		String seqFilename = gd2.getNextString();
		if ( ! seqFilename.endsWith( ".xml" ) )
			seqFilename += ".xml";
		final File seqFile = new File( seqFilename );
		final File parent = seqFile.getParentFile();
		if ( parent == null || !parent.exists() || !parent.isDirectory() )
		{
			IOFunctions.println( "Invalid export filename " + seqFilename );
			return null;
		}
		final String hdf5Filename = seqFilename.substring( 0, seqFilename.length() - 4 ) + ".h5";
		final File hdf5File = new File( hdf5Filename );
		final boolean appendToExistingFile = gd2.getNextBoolean();

		final int cropOffsetX = Multi_View_Fusion.cropOffsetXStatic;
		final int cropOffsetY = Multi_View_Fusion.cropOffsetYStatic;
		final int cropOffsetZ = Multi_View_Fusion.cropOffsetZStatic;
		final int scale = Multi_View_Fusion.outputImageScalingStatic;
		return new Parameters( conf, resolutions, subdivisions, cropOffsetX, cropOffsetY, cropOffsetZ, scale, fusionDirectory, filenamePattern, numSlices, minValueStatic, maxValueStatic, seqFile, hdf5File, appendToExistingFile );
	}

	protected boolean updateProposedMipmaps( final String fusionDirectory, final SPIMConfiguration conf )
	{
		final Pair< String, Integer > pair = detectPatternAndNumSlices( new File ( fusionDirectory ), conf.timepoints[0] );
		if ( pair != null )
		{
			final String filenamePattern = pair.getA();
			final int numSlices = pair.getB();
			final String fn = fusionDirectory + "/" + String.format( filenamePattern, conf.timepoints[0], conf.channels[0], 0 );
			final ImagePlus imp = new ImagePlus( fn );
			final int width = imp.getWidth();
			final int height = imp.getHeight();
			imp.close();
			final Dimensions size = new FinalDimensions( new int[] { width, height, numSlices } );
			final VoxelDimensions voxelSize = new FinalVoxelDimensions( "px", 1, 1, 1 );
			final ExportMipmapInfo info = ProposeMipmaps.proposeMipmaps( new BasicViewSetup( 0, "", size, voxelSize ) );
			autoSubsampling = ProposeMipmaps.getArrayString( info.getExportResolutions() );
			autoChunkSizes = ProposeMipmaps.getArrayString( info.getSubdivisions() );
			return true;
		}
		else
			return false;
	}

	public static Pair< String, Integer > detectPatternAndNumSlices( final File dir, final int someTimepoint )
	{
		final File subdir = new File( dir.getAbsolutePath() + "/" + someTimepoint );
		if ( subdir.isDirectory() )
		{
			final Pair< String, Integer > pair = detectPatternAndNumSlices( subdir, someTimepoint );
			if ( pair == null )
				return null;
			return new ValuePair< String, Integer >( "%1$d/" + pair.getA(), pair.getB() );
		}

		String zeros = "";
		for ( int digits = 1; digits < 5; ++digits )
		{
			zeros = zeros + "0";
			final String suffixFusion = "_" + zeros + ".tif";
			final String suffixDeconvolution = "_z" + zeros + ".tif";
			final File[] files = dir.listFiles( new FilenameFilter()
			{
				@Override
				public boolean accept( final File dir, final String name )
				{
					return name.endsWith( suffixFusion ) || name.endsWith( suffixDeconvolution );
				}
			} );
			if ( files != null && files.length > 0 )
			{
				final String name = files[ 0 ].getName();
				int tStart = name.indexOf( "_t" ) + 2;
				if ( name.substring( tStart, tStart + 1 ).equals("l") )
					tStart++;
				final int tEnd = name.indexOf( "_", tStart );
				final int cStart = name.indexOf( "_ch", tEnd ) + 3;
				final int cEnd = name.indexOf( "_", cStart );
				final String pattern = name.substring( 0, tStart ) + "%1$d" + name.substring( tEnd, cStart ) + "%2$d" + name.substring( cEnd, name.length() - 4 - digits ) + "%3$0" + digits + "d.tif";
				IOFunctions.println( "detected pattern = " + pattern );

				final String prefix = name.substring( 0, name.length() - 4 - digits );
				final File[] files2 = dir.listFiles( new FilenameFilter()
				{
					@Override
					public boolean accept( final File dir, final String name )
					{
						return name.startsWith( prefix );
					}
				} );
				final int numSlices = files2.length;
				IOFunctions.println( "detected numSlices = " + numSlices );

				return new ValuePair< String, Integer >( pattern, new Integer( numSlices ) );
			}
		}
		return null;
	}

	protected static double loadZStretching( final String file )
	{
		final BufferedReader in = TextFileAccess.openFileRead( file );
		double z = -1;
		try
		{
			while ( in.ready() )
			{
				final String line = in.readLine();

				if ( line.contains( "z-scaling:") )
					z = Double.parseDouble( line.substring( line.indexOf( "ing" ) + 4, line.length() ).trim() );
			}
		}
		catch (final IOException e)
		{
		}

		return z;
	}

	public static void main(final String[] args)
	{
		new ExportSpimFusionPlugIn().run( null );
	}
}
