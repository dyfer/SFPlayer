SFPlayer {
	var <path, <outbus, <server, <autoShowOpenDialog, <>autoBootServer, <>autoSetSampleRate, <>autoSetOutputChannels;
	var <buffer, <sf, cond, <curSynth, <synthName;
	var clock, <skin;
	var <cues, offset, lastStart;
	var <amp, <isPlaying = false, <isPaused = false, <bufferPreloaded = false, <isStopping = false, <isStarting = false, wasPlaying, <startTime, lastTimeForCurrentRate = 0;
	// var <openFilePending = false, <openGUIafterLoading = false;
	var <>duplicateSingleChannel = true;
	var rateVar, addActionVar, targetVar, bufsizeVar, <>multiplyBufsizeByNumChannels = true;
	var <>switchTargetWhilePlaying = true;
	var <view;
	var <attRelTime = 0.02;
	var <>defaultOpenPath;
	var <>followAddAction = true, <>followTarget = true, <>followAmp = true, <>followOutbus = true, <>followRate = true, <>followPlayStopPause = true, <>followStartTime = true; //set which parameters will be updated when this sfplayer is registered as a dependant of another one

	*new {arg path, outbus, server, skin, autoShowOpenDialog = true, autoBootServer = true, autoSetSampleRate = true, autoSetOutputChannels = true; /*autoSetSampleRate and autoSetOutputChannels are only exectuded it the server is not booted*/
		^super.newCopyArgs(path, outbus, server, autoShowOpenDialog, autoBootServer, autoSetSampleRate, autoSetOutputChannels).initSFPlayer(skin);
	}

	initSFPlayer {arg argSkin;
		skin = argSkin;// ?? {SFPlayerSkin.default};
		server = server ?? Server.default;
		this.rate_(1);
		// rateVar = 1;
		this.addAction_(0);
		// addActionVar = 0;
		// targetVar = 1;
		this.target_(server.defaultGroup);
		outbus ?? {this.outbus_(0)};
		bufsizeVar = 2.pow(17).asInteger;
		amp = 1;
		// server.serverRunning.not({server.boot}); //this was not working (missing .if); we have waitForBoot in runSetup anyway
		offset = 0;
		(path.isNil && autoShowOpenDialog).if({
			this.load;
		}, {
			path !? {this.runSetup};
		});
	}

	path_ {arg pathArg;
		path = pathArg;
		this.runSetup;
		this.reset;
	}

	load {arg pathArg;
		if(pathArg.isNil, {
			Dialog.openPanel({arg pathArg;
				this.path_(pathArg);
			}, path: defaultOpenPath);
		}, {
			this.path_(pathArg);
		})
	}

	runSetup {
		sf = SoundFile.new;
		{
			sf.openRead(path);
			cond = Condition.new;
			if(autoBootServer, {
				if(server.options.numOutputBusChannels < sf.numChannels, {
					if(server.serverRunning.not && autoSetOutputChannels, { //if server is not running, set the number of output channels
						format("%: setting server's options.numOutputBusChannels to %", this.class.name, sf.numChannels).postln;
						server.options.numOutputBusChannels_(sf.numChannels);

					}, {
						if(outbus < server.options.numOutputBusChannels, {
							format("%: server's options.numOutputBusChannels (%) is lower than soundfile's numChannels (%)", this.class.name, server.options.numOutputBusChannels, sf.numChannels).warn;
						});
					});
				});
				if(server.serverRunning.not && server.options.sampleRate.isNil && autoSetSampleRate, { //also set the samplerate if server is not running
					//and sampleRate too rate too
					format("%: setting server's options.sampleRate to %", this.class.name, sf.sampleRate).postln;
					server.options.sampleRate_(sf.sampleRate);
				});
				if(server.serverRunning, {
					this.buildSD;
				}, {
					server.waitForBoot({
						this.buildSD;
					});
				});
			}, {
				this.buildSD;
			});
			sf.close;
			isPlaying = false;
			wasPlaying = false;
			startTime = 0.0;
			this.changed(\loaded);
		}.try({"Soundfile could not be opened".warn});
	}

	buildSD {
		synthName = "SFPlayer"++sf.numChannels;
		SynthDef(synthName, {arg gate = 1, buffer, amp = 1, rate = 1, outbus;
			var diskin;
			diskin = VDiskIn.ar(sf.numChannels, buffer, (BufSampleRate.kr(buffer) / SampleRate.ir) * rate);
			((sf.numChannels == 1) && duplicateSingleChannel).if({
				diskin = diskin.dup
			});
			Out.ar(outbus, diskin *
				EnvGen.kr(Env([0, 1, 0], [attRelTime, attRelTime], \sin, 1), gate, doneAction: 2) *
				Lag.kr(amp, 0.1))
		}).add;
	}

	loadBuffer {arg sTime = 0, completionMessage;
		var localBufSize;
		// "loading buffer".postln;
		if(multiplyBufsizeByNumChannels, {
			localBufSize = this.bufsize * sf.numChannels;
		}, {
			localBufSize = this.bufsize;
		});
		// bufsize = bufsize * sf.numChannels; //should I multiplet
		// server.sendMsg(\b_alloc, bufnum = server.bufferAllocator.alloc, bufsize, sf.numChannels,
		// [\b_read, bufnum, path, startTime * sf.sampleRate, bufsize, 0, 1]);
		buffer = Buffer.cueSoundFile(server, path, (sTime * sf.sampleRate).asInteger, sf.numChannels, localBufSize, completionMessage);
	}

	freeBuffer {arg when;
		var oldBuf;
		when.notNil.if({
			oldBuf = buffer;
			{
				try{oldBuf.close};
				oldBuf.free;
				// "buffer freed (scheduled)".postln;
			}.defer(when);
		}, {
			try{buffer.close};
			buffer.free;
			// "buffer freed right away".postln;
		});
	}

	bufsize {^bufsizeVar}
	bufsize_ {arg val; bufsizeVar = val}

	addAction  {^addActionVar}
	addAction_ {arg val;
		// val = Node.addActions[val];
		val !? {
			addActionVar = val;
			this.changed(\addAction, this.addAction);
		};
	}

	target {^targetVar}
	target_ {arg val;
		targetVar = val;
		this.changed(\target, val, switchTargetWhilePlaying);
		if(isPlaying && switchTargetWhilePlaying, {
			Node.actionNumberFor(this.addAction).switch(
				0, {curSynth.moveToHead(this.target.asTarget)}, //head
				1, {curSynth.moveToTail(this.target.asTarget)}, //tail
				2, {curSynth.moveBefore(this.target.asTarget)}, //before
				3, {curSynth.moveAfter(this.target.asTarget)}, //after
				4, {format("%: can't replace % while playing", this.class.name, this.target.asTarget).warn}, //replace
			)
		});
	}

	rate {^rateVar}
	rate_ {arg val;
		val !? {rateVar = val};
		try{clock.tempo = rateVar};
		curSynth !? {curSynth.set(\rate, this.rate)};
		this.changed(\rate, this.rate);
	}

	play {arg bufsize, addAction, target, rate;
		// format("startTime in play: %", startTime).postln;
		(isPlaying.not and: {startTime < sf.duration} and: synthName.notNil and: server.serverRunning).if({
			bufsize !? {bufsizeVar = bufsize};
			addAction !? {addActionVar = addAction};
			target !? {targetVar = target};
			rate !? {rateVar = rate};
			isPlaying = true; //moved here so we have this status synchronously...
			// "isStarting: ".post;isStarting.postln;
			isStarting.not.if({ //prevent starting if we're already in the play routine
				isStarting = true;
				Routine.run({
					lastStart = startTime;
					// clock.sched(sf.duration - startTime + 0.1, {this.stop});
					if(bufferPreloaded.not && isPlaying, {
						this.changed(\isPaused, true); //force preloading buffer in dependants, for synchronization
						this.loadBuffer(startTime);
						// bufferPreloaded = false;//not sure if needed here
					}, {
						// "buffer preloaded already or playback aborted".postln;
					});
					server.sync; //(cond);
					if(isPlaying.not, { //if we were stopped in the meantime, call .stop to free resources and don't start playing;
						// "aborting playback".postln;
						isStarting = false;
						this.stop;
					}, {
						// "starting synth".postln;
						clock = TempoClock(this.rate, startTime);
						clock.schedAbs(sf.duration + (0.2), {this.stop}); //should I move this to nodewatcher?
						// server.sendMsg(\s_new, "SFPlayer"++sf.numChannels,
						// curNode = server.nodeAllocator.alloc(1), addAction, target,
						// \buffer, bufnum, \amp, amp, \outbus, outbus, \rate, rate);
						curSynth = Synth(synthName, [\buffer, buffer, \amp, amp, \outbus, outbus, \rate, rateVar], targetVar, addActionVar);
						// curNode = curSynth.nodeID;
						isPaused = false;
						isStarting = false;
						this.changed(\isPlaying, this.isPlaying);
						this.changed(\isPaused, isPaused); //should this be moved before \isPlaying?
					});
				})
			}, {
				// "routine seems running, aborting start".postln;
			});
		})
	}

	pause {
		isPlaying.if({
			var now = this.curTime;
			// "pause playing".postln;
			this.stop(false);
			this.startTime_(now);
		}, {
			if(isPaused.not, {
				// "pause stopped".postln;
				this.loadBuffer(startTime);
				// "preloading buffer".postln;
				bufferPreloaded = true;
				isPaused = true;
				this.changed(\isPaused, isPaused);
			});
		});
	}

	stop {arg updateStart = true; //I think this should be false by default?
		// var oldbufnum;
		(isPlaying || isPaused).if({
			// "stopping".postln;
			// isStopping = true;
			clock.stop;
			curSynth !? {curSynth.release; curSynth = nil};
			// oldbufnum = bufnum;
			isPlaying = false;
			isPaused = false;
			bufferPreloaded = false;
			this.changed(\isPlaying, isPlaying, updateStart);
			this.changed(\isPaused, isPaused);
			this.freeBuffer(0.2);
			// SystemClock.sched(0.2, {
			// 	server.sendBundle(nil, [\b_close, oldbufnum], [\b_free, oldbufnum]);
			// 	server.bufferAllocator.free(oldbufnum);
			//
			// 	// isStopping = false;
			// });
			updateStart.if({{this.startTime_(lastStart)}.defer(0.1)}); //this can probably be substituted with this.changed?
		}, {
			this.changed(\startTime, startTime);// just update dependents if not playing
		});
	}

	outbus_ {arg newOut, updateMenu = true;
		outbus = newOut;
		isPlaying.if({
			curSynth.set(\outbus, outbus);
			// server.sendMsg(\n_set, curNode, \outbus, outbus);
		});
		this.changed(\outbus, outbus, updateMenu);
	}

	amp_ {arg newAmp, source; //source: \number, \slider or none
		amp = newAmp;
		isPlaying.if({
			curSynth.set(\amp, amp);
			// server.sendMsg(\n_set, curNode, \amp, amp)
		});
		this.changed(\amp, amp, source);
	}

	curTime {
		var time;
		if(isPlaying, {
			try {
				time = clock.beats;
			}
		});
		time = time ?? startTime; //if getting from the clock failed, revert to startTime
		^time;
	}

	startTime_ {arg newStartTime;
		newStartTime !? {startTime = (newStartTime + offset).max(0).min(sf.duration)};
		// format("new startTime: %", startTime).postln;
		this.changed(\startTime, startTime);
		if(isPlaying, { //jump to that position
			this.stop(false); //don't update start time
			this.play;
		});
		// ---- !!!! ----
		//not sure what the offset is for below

		// hasGUI.if({
		// 	sfView.timeCursorPosition_((startTime * sf.sampleRate).round);
		// 	timeString.string_(startTime.asTimeString[3..10]);
		// 	cueOffsetNum.value_(0);
		// 	offset = 0;
		// })
	}

	gui {arg argBounds, doneAction, onCloseAction, parent;
		if(view.isNil, {
			view = SFPlayerView(this, argBounds, doneAction, {onCloseAction.();  view = nil}, parent)
		}, {
			view.front;
		});
	}

	pausePlay {
		isPlaying.if({
			wasPlaying = true;
			this.stop;
		});
	}

	playPaused {
		// startTime = sfView.timeCursorPosition / sf.sampleRate; //fixme what was this?
		wasPlaying.if({
			this.play;
			wasPlaying = false;
		}, {
			// timeString.string_(startTime.asTimeString[3..10]);
		})
	}

	addCue {arg key, time, sort = true, redraw = true;
		if(time.isNil, {
			if(isPlaying, {
				time = this.curTime;
			}, {
				time = startTime;
			})
		});
		key.notNil.if({
			(time < sf.duration).if({
				//			(cues.notNil and: {cues[key].notNil}).if({
				//				this.removeCue(key, false, false);
				//			});
				cues = cues.add([key, time]);
			}, {
				"You tried to add a cue past the end of the soundfile".warn;
			});
			sort.if({this.sortCues});
			// redraw.if({this.drawCues}); //move to update
			this.changed(\cues, cues);
		})
	}

	removeCue {arg key, sort = true, redraw = true;
		var idx;
		cues.do({arg thisCue, i;
			(thisCue[0] == key).if({
				idx = i;
			});
		});
		idx.notNil.if({
			cues.removeAt(idx);
		}, {
			"Cue not found".warn
		});
		sort.if({	this.sortCues;});
		// redraw.if({this.drawCues}); //move to update
		this.changed(\cues, cues);
	}

	sortCues {
		cues.sort({arg a, b; a[1] < b[1]});
	}

	loadCues {arg path;
		path.isNil.if({
			// Dialog.getPaths({arg paths;
			Dialog.openPanel({arg thisPath;
				// cues = paths[0].load;
				cues = thisPath.load;
				// this.drawCues;
				this.changed(\cues, cues);
			})
		}, {
			cues = path.load;
			// this.drawCues;
			this.changed(\cues, cues);
		});
	}

	saveCues {arg path;
		path.isNil.if({
			Dialog.savePanel({arg thisPath;
				cues.writeArchive(thisPath);
			})
		}, {
			cues.writeArchive(path)
		})
	}

	setCues {arg newCues, forSure = false;
		if(forSure, {
			cues = newCues;
			this.changed(\cues, cues);
		}, {
			"you need to confirm overriding cues by setting second argument to true".warn;
		});
	}

	playFromCue {arg key, idx;
		var newStart, tmp;
		idx = idx ?? {
			cues.do({arg thisCue, i;
				(thisCue[0] == key).if({
					tmp = i;
				});
			});
			tmp;
		};
		idx.notNil.if({
			(idx == -1).if({
				newStart = 0.0;
			}, {
				newStart = cues[idx][1];
			})
		});
		this.startTime_(newStart);
		// this.play;
	}

	offset_ {arg newOffset; //time offset for playing from cue
		var tmp;
		tmp = startTime + offset;
		offset = newOffset;
		this.startTime_(tmp);
	}

	reset {
		isPlaying.if({this.stop});
		{
			// cueMenu.value_(0); //fixme move to update
			isStarting = false;
			this.startTime_(0);
			this.amp_(1);
			this.rate_(1);
		}.defer(0.11) //fixme: do we need defer here?
	}

	free {
		this.stop;
		//also clean up resources in the future here
		this.view !? {
			{this.view.window.notNil.if({this.view.window.close}, {this.view.view.close})}.defer;
		}
	}

	close {
		this.free;
	}

	update {arg who, what ...args;
		var value = args[0];
		// args[0] is the value
		// "update fired, what: ".post;
		// what.post;
		// ": ".post; args.postln;
		if(who != this, {
			what.switch(
				\addAction, {if(followAddAction, {this.addAction_(value)})},
				\target, {if(followTarget, {this.target_(value)})},
				\amp, {if(followAmp, {this.amp_(value)})},
				\outbus, {if(followOutbus, {this.outbus_(value)})},
				\rate, {if(followRate, {this.rate_(value)})},
				\isPlaying, {
					if(followPlayStopPause, {
						if(value, {
							this.play;
						}, {
							this.stop(args[1]);//updateStart
						})
					})
				},
				\isPaused, {if(followPlayStopPause && value, {this.pause})},
				\startTime, {if(followStartTime, {this.startTime_(value)})}
			)
		})
	}

}

SFPlayerSkin {
	classvar <>default;
	var <>string, <>background, <>sfBackground, <>sfWaveform, <>sfCursor,
	<>cueLabel, <>cueLine;

	*new {arg string, background, sfBackground, sfWaveform, sfCursor, cueLabel, cueLine;
		^super.newCopyArgs(string, background, sfBackground, sfWaveform,
			sfCursor, cueLabel, cueLine).initSFPlayerSkin;
	}

	initSFPlayerSkin {
		string = string ?? {Color.grey(0.8)};
		background = background ?? {Color.grey(0.4)};
		sfBackground = sfBackground ?? {Color.grey(0.8)};
		sfWaveform = sfWaveform ?? {Color(0.4, 0.4, 0.7)};
		sfCursor = sfCursor ?? {Color.blue};
		cueLabel = cueLabel ?? {Color.black};
		cueLine = cueLine ?? {Color.grey(0.2)}
	}

	*initClass {
		default = this.new;
	}
}

SFPlayerView {
	var <player;
	var <bounds, <parent, <doneAction, <onCloseAction, <skin, <>stopPlayerOnClose;
	var <window, <view, <bottomView, <advancedButton, outMenu, playButton, pauseButton, ampSlider, ampNumber, rateNumber, targetText, addActionMenu;
	var cueOffsetNum, cueMenu;
	var scope;
	var <timeString, <timeStringSm, <sfView, <cuesView, <gridView, <timeGrid, <zoomSlider, guiRoutine, <filenameString;
	var <skin;
	// var tempBounds, tempAction;
	// var curTime; // here vs player???
	var ampSpec;
	var <showHours = false; //set automatically at load
	var <>updateTime = 0.1;
	var isSelectingNewStartTime = false; //changes to true when setting soundfileview's cursor; used to prevent updating then
	var <>requireShiftSpaceForPause = false;
	var <>autoScroll = true;

	*new {arg player, bounds, doneAction, onCloseAction, parent, skin, stopPlayerOnClose = true;
		^super.newCopyArgs(player, bounds, parent, doneAction, onCloseAction, skin, stopPlayerOnClose).makeGui;
	}

	makeGui {
		skin ?? {skin = SFPlayerSkin.default};

		// format("player: %, its amp: %", player, player.amp).postln;
		ampSpec = [-90, 12].asSpec;
		// bounds = argBounds ?? {Rect(200, 200, 980, 600)};
		// bounds ?? {bounds = Rect(200, 200, 980, 600)};
		bounds ?? {
			if(parent.notNil, {
				bounds = 980@600;
			}, {
				bounds = Rect.aboutPoint(Window.screenBounds.center, 490, 300)
			});
		};
		parent.isNil.if({
			window = Window("", bounds);
			view = window.view;
			window.front;
		}, {
			view = View(parent, bounds)
		});
		view.background_(skin.background);
		view.mouseDownAction_({arg view, x, y, modifiers, buttonNumber, clickCount;
			var sfBounds = sfView.bounds;
			if((x <= sfBounds.left) && (y >= sfBounds.top) && (y <= sfBounds.bottom), {
				if(player.isPlaying.not, {player.startTime_(0)});
			});
		});// set startTime to 0 when clicking to the left of the soundfileview

		view.keyDownAction_({arg view, char, modifiers, unicode, keycode, key;
			// key.postln;
			key.switch(
				32, { //space
					if(player.isPlaying, {
						if(requireShiftSpaceForPause, {
							if(modifiers == 131072, {
								player.pause;
							})
						}, {
							player.pause;
						});
					}, {
						player.play;
					})
				},
				83, { //s - stop
					player.stop;
				},
				80, { //p - pause
					player.pause;
				},
				16777216, {//esc - go to 0
					if(player.isPlaying.not, {
						player.startTime_(0);
					});
				},
				16777234, {// left arrow
					this.goToPreviousCue;
				},
				16777236, {//right arrow
					this.goToNextCue;
				}
			)
		});

		view.focusGainedAction_({
			outMenu.focus(false);
		});

		// timeGrid = DrawGrid(nil, ControlSpec(0, player.sf.duration, units: \s).grid, nil);
		timeGrid = DrawGrid(nil, nil, nil);
		timeGrid.fontColor_(skin.string);
		timeGrid.font_(Font("Arial", 10));
		timeGrid.gridColors_([skin.string, nil]);
		view.onClose_({
			stopPlayerOnClose.if({player.isPlaying.if({player.stop})});
			player.removeDependant(this);
			onCloseAction.();
		});
		view.layout_(
			VLayout(
				HLayout(
					VLayout(
						filenameString = StaticText()
						.font_(Font("Arial", 14))
						.background_(skin.background)
						.stringColor_(skin.string)
						.align_(\center)
						.visible_(parent.notNil)
						,
						View()
						.fixedHeight_(1)
						.background_(skin.string)
						.visible_(parent.notNil), //horizontal line
						HLayout(
							nil,
							timeString = StaticText()
							.font_(Font("Arial", 72))
							.stringColor_(skin.string)
							.align_(\right)
							.fixedHeight_(80)
							// .string_(player.startTime.asTimeString[3..10])
							// .string_(0.asTimeString[3..7])
							// .minWidth_(200)
							.fixedSize_(280@80)
							,
							VLayout(
								24,
								timeStringSm = StaticText()
								.font_(Font("Arial", 36))
								.stringColor_(skin.string)
								.canFocus_(true)
								// .string_(player.startTime.asTimeString[3..10])
								// .string_("00")
								// .fixedHeight_(44)
								// .fixedWidth_(60)
								.fixedSize_(60@40)
								,
							)
						),
					).margins_([0, 0, 0, 0]).spacing_(2),
					VLayout(
						HLayout(
							Button.new()
							.states_([
								["◼", skin.string, skin.background]])
							.canFocus_(false)
							.action_({player.stop})
							.fixedWidth_(46)
							.fixedHeight_(46)
							,

							playButton = Button.new()
							.states_([
								["►", skin.string, skin.background],
								["►", skin.background, skin.string]
							])
							// .focus(true)
							.canFocus_(false)
							// .fixedHeight_(32)
							.fixedHeight_(46)
							.action_({arg button;
								// 	[{player.pause}, {player.play}][button.value].value;
								button.value_(button.value.asBoolean.not.asInteger); //ugly - reset state
								player.play
							})
							// .mouseDownAction_({
							// 	false;
							// })
							// .mouseUpAction_({
							// 	player.play;
							// 	false;
							// })
							.fixedWidth_(80),

							pauseButton = Button.new()
							.states_([
								["❙❙", skin.string, skin.background],
								["❙❙", skin.background, skin.string]
							])
							.canFocus_(false)
							// .action_({arg button; player.pause})
							.mouseDownAction_({false;})
							.mouseUpAction_({
								player.pause;
								false;
							})
							.fixedWidth_(46)
							.fixedHeight_(46)
						).spacing_(0),
						HLayout(
							Button.new()
							.states_([["❙◀️", skin.string, skin.background]])
							.canFocus_(false)
							.action_({arg button; player.startTime_(0)})
							.fixedWidth_(50)
							.fixedHeight_(30)
							,
							StaticText()
							// .font_(Font(size: 8))
							.stringColor_(skin.string)
							.string_("cue:")
							.align_(\center)
							,

							Button.new()
							.states_([["❙◀️◀️", skin.string, skin.background]])
							.canFocus_(false)
							.font_(Font(size: 10))
							.fixedWidth_(40)
							.fixedHeight_(30)
							.action_({
								this.goToPreviousCue;
							})
							// .action_({arg button; player.pause}) //previous cue from menu
							,
							Button.new()
							.states_([["►►❙", skin.string, skin.background]])
							.canFocus_(false)
							.font_(Font(size: 10))
							.fixedWidth_(40)
							.fixedHeight_(30)
							.action_({
								this.goToNextCue;
							})
							// .action_({arg button; player.pause}) //next cue from menu
							,
						).spacing_(0)
					),

					GridLayout.rows(

						[
							StaticText()
							.string_("Amplitude (in dB)")
							.stringColor_( skin.string),

							ampNumber = NumberBox()
							.value_(player.amp.ampdb)
							.background_(skin.background)
							.normalColor_( skin.string)
							.action_({arg view;
								player.amp_(view.value.dbamp, \number);
								// ampSlider.value_(ampSpec.unmap(me.value);
								// view.focus(true));
								view.focus(false);
							}).maxWidth_(50),
						],
						[
							[
								ampSlider = Slider()
								.value_(ampSpec.unmap(player.amp))
								.canFocus_(false)
								.orientation_(\horizontal)
								.maxHeight_(20)
								// .maxWidth_(200)
								.mouseDownAction_({arg view, x, y, mod, button, cCount;
									"mousedown fires".postln;
									if((button == 0) && (cCount > 1), {
										{player.amp_(1)}.defer(0.05); //dirty...
									});
									0; //return non-bool to maintain view's response
								})
								.action_({arg me;
									player.amp_(ampSpec.map(me.value).round(0.1).dbamp, \slider);
									// ampNumber.value_(ampSpec.map(me.value).round(0.1))
								}),
								columns: 2
							]
						],
						[
							StaticText()
							.string_("Outbus")
							.stringColor_(skin.string),
							// outMenu = PopUpMenu()
							// .items_(player.server.options.numAudioBusChannels.collect({arg i; i.asString})) //fixme this might need updating after boot!

							outMenu = NumberBox()
							.value_(player.outbus ?? {0})
							.action_({arg menu; player.outbus_(menu.value, false)})
							.keyDownAction_({arg view, char, modifiers, unicode, keycode, key;
								// char.postln; unicode.postln;
								if((unicode == 3) || (unicode == 13), {view.focus(false)}); //focus on play only after Enter/Return
							})
							.stringColor_( skin.string )
							.normalColor_( skin.string )
							.clipLo_(0)
							.clipHi_(player.server.options.numAudioBusChannels) //this might need to be updated after boot?
							.step_(1)
							.background_(skin.background)
							.focus(false)
							.fixedWidth_(50),
						]
					),
					nil,
					VLayout(
						HLayout(
							advancedButton = Button()
							.states_([
								["Advanced\noptions", skin.string, skin.background],
								["Advanced\noptions", skin.background, skin.string]
							])
							.canFocus_(false)
							.action_({arg view;
								bottomView.visible_(view.value.asBoolean);
							}),
							VLayout(
								Button()
								.states_([["?", skin.string, skin.background]])
								.canFocus_(false)
								.action_({arg view;
									SFPlayerView.openHelpFile;
								})
								.maxWidth_(20)
								,
								nil
							)
						)
						,
						nil,
						Button.new()
						.states_([
							["Reset", skin.string, skin.background]
						])
						.canFocus_(false)
						.action_({player.reset}),
					)
				).margins_([0, 0, 0, 0]), //end of top section with time, play/stop, amp etc
				[
					VLayout(
						zoomSlider = RangeSlider().orientation_(\horizontal)
						.lo_(0).range_(1)
						.background_(skin.background)
						.knobColor_(Color.black)
						.canFocus_(false)
						// .maxHeight_(16)
						.action_({arg view;
							var divisor, rangeStart;
							rangeStart = view.lo;
							divisor = 1 - view.range;
							if(divisor < 0.0001) {
								rangeStart = 0;
								divisor = 1;
							};
							sfView.xZoom_(view.range * player.sf.duration)
							.scrollTo(rangeStart / divisor);
							this.prScrollAction(false);
						}),
						[
							StackLayout(
								cuesView = UserView().acceptsMouse_(false),
								sfView = SoundFileView()
								.canFocus_(false)
								.timeCursorColor_(skin.sfCursor)
								.gridOn_(false)
								.timeCursorOn_(true)
								.background_(skin.sfBackground)
								.rmsColor_(Color.gray(1, 0.2))
								// .mouseDownAction_({player.pausePlay})
								// .mouseUpAction_({player.playPaused})
								.mouseDownAction_({arg view, x, y, mod, button, cCount;
									// "mousedown fires".postln;
									if(button == 0, {
										isSelectingNewStartTime = true;
									});
									0; //return non-bool to maintain view's response! see "key and mouse even processing" in help...
								})
								.mouseUpAction_({arg view, x, y, mod, button, cCount;
									// "mouseup fires".postln;
									if(button == 0, {
										player.startTime_(view.timeCursorPosition / player.sf.sampleRate);
										isSelectingNewStartTime = false;
									});
									0;
								})
								.mouseMoveAction_({arg view;
									this.prScrollAction(true);
								})
								.mouseWheelAction_({arg view, x, y, modifiers, xDelta, yDelta;
									var curSecs, newSecs, oldPos;
									var divisor, rangeStart, rangeSize, oldRangeSize;
									var scrollDiff;
									// "[x, y, modifiers, xDelta, yDelta]: ".post; [x, y, modifiers, xDelta, yDelta].postln;
									//modifiers (macos)
									//131072 shift
									//1048576 cmd
									//1179648 cmd+shift
									//524288 alt
									//1572864 cmd+alt
									//1310720 cmd+ctrl

									curSecs = view.xZoom;
									oldRangeSize = curSecs / player.sf.duration;
									if(yDelta != 0, {
										rangeSize = oldRangeSize + (oldRangeSize * yDelta * 0.01);
										rangeSize = rangeSize.min(1);
										rangeStart = view.scrollPos * (1 - rangeSize);
										view.xZoom_(rangeSize * player.sf.duration);
										view.scroll(yDelta * -0.01 * x.linlin(view.bounds.width * 0, view.bounds.width * 1, 0, 1.01));
									}, {
										rangeSize = oldRangeSize;
										rangeStart = view.scrollPos * (1 - rangeSize);
									});

									if(xDelta != 0, {
										view.scroll(xDelta * -0.01);
									});

									this.prScrollAction(true);
								})
								.timeCursorPosition_(0),
							).mode_(\stackAll),
							stretch: 10
						],
						gridView = UserView()
						.drawFunc_({arg view;
							timeGrid.bounds = Rect(0, 0, view.bounds.width, view.bounds.height);
							timeGrid.draw;
						})
						.minHeight_(12),
					).margins_([0, 0, 0, 0]).spacing_(1),
					stretch: 10
				],

				//bottom secion
				/* cues */
				bottomView = View().layout_(
					HLayout(
						GridLayout.rows(//cues
							[
								[
									StaticText()
									.string_("Cues")
									.stringColor_( skin.string)
									.align_(\center),
									columns: 5
								],
							],
							[
								StaticText()
								.string_("Time offset:")
								.stringColor_(skin.string),
								cueOffsetNum = NumberBox()
								.value_(0)
								.background_(skin.background)
								.normalColor_( skin.string)
								.action_({arg thisBox;
									player.offset_(thisBox.value);
									view.focus(true)
								})
								.maxWidth_(60),

								StaticText()
								.string_("Cue:")
								.stringColor_( skin.string)
								.align_(\right)
								,
								[
									cueMenu = PopUpMenu()
									.items_(player.cues.asArray)
									.stringColor_(skin.string)
									.background_(skin.background)
									.canFocus_(false)
									.allowsReselection_(true)
									// .mouseUpAction_({"MouseUp".postln;})
									// .mouseDownAction_({arg view;
									// player.isPlaying.if({player.wasPlaying = true;player.stop});
									// view.value_(0)
									// })
									.action_({arg thisMenu;
										var idx;
										idx = thisMenu.value - 1;
										(idx >= 0).if({
											player.playFromCue(player.cues[idx][0], idx);
										}, {
											player.playFromCue(\none, -1)
										});
										// player.wasPlaying.if({player.play; player.wasPlaying = false;})
									})
									.minWidth_(300),
									columns: 1
								],

								Button()
								.states_([
									["Load cues", skin.string, skin.background]
								])
								.canFocus_(false)
								.action_({
									player.loadCues
								}),
							],
							[
								[
									StaticText()
									.string_("Add cues (\\key or [\\key, time])") // time can be a number in seconds or string "(hh:)mm:ss.xxx"; if \key alone is provided, current cursor time is used
									.stringColor_(skin.string)
									// .minWidth_(220)
									,
									columns: 3
								],
								TextField()
								.stringColor_( skin.string)
								.background_(skin.background)
								.action_({arg view;
									var vals;
									try{vals = view.string.interpret};
									vals ?? {
										(view.string.stripWhiteSpace.size > 0).if({
											vals = view.string.split($,).collect({|key, inc|
												inc.even.if({
													key.stripWhiteSpace.asSymbol;
												}, {
													var ret;
													protect{ret = key.interpret} {ret = ret ? key};
													ret;
												})
											})
										});
									};
									vals.isKindOf(Symbol).if({
										vals = [vals, nil]
									}); //take time from the cursor
									if(vals.isKindOf(Collection), { //by now it needs to be...
										vals = vals.collect({arg val, inc; val.isKindOf(String).if({val.asSecs}, {val})}); //convert time string to seconds
										vals.isKindOf(Array).if({
											vals = vals.flat.clump(2);
										}, {
											vals = vals.asArray.flat.clump(2)
										});
										vals[0].isKindOf(Array).if({
											vals.do({arg thisPair;
												player.addCue(thisPair[0], thisPair[1], false)
											});
											player.sortCues;
										}, {
											player.addCue(vals[0], vals[1], true)
										});
										view.focus(false);
										view.string_("");
									});
								}),
								Button()
								.states_([
									["Save cues", skin.string, skin.background]
								])
								.canFocus_(false)
								.action_({
									player.saveCues
								}),
							],
							[
								[
									StaticText()
									.string_("Remove cues (\\key or [\\key])")
									.stringColor_(skin.string)
									.minWidth_(200)
									,
									columns: 3
								],
								TextField()
								.stringColor_( skin.string)
								.background_(skin.background)
								.action_({arg view;
									var vals;
									try{vals = view.string.interpret};
									vals ?? {vals = view.string.split($,).collect({|key| key.asSymbol})};
									vals.isKindOf(Array).if({
										vals = vals.flat;
									}, {
										vals = vals.asArray
									});
									vals.do({arg thisKey;
										player.removeCue(thisKey, false)
									});
									player.sortCues;
									view.focus(false);
									view.string_("");
								}),
								Button()
								.states_([
									["Hide cues",  skin.string, skin.background],
									["Show cues",  skin.string, skin.background]
								])
								.canFocus_(false)
								.action_({arg button;
									(button.value == 0).if({
										this.drawCues
									}, {
										this.hideCues
									})
								}),
							]
						),
						20, //space
						GridLayout.rows( //playback
							[

								[
									StaticText()
									.string_("Playback")
									.stringColor_( skin.string)
									.align_(\center),
									columns: 2
								],
							],
							[
								StaticText()
								.string_("addAction")
								.stringColor_(skin.string),

								addActionMenu = PopUpMenu()
								.items_(this.getAddActionsArray)
								.value_(this.getAddActionIndex(player.addAction))
								.action_({arg view; player.addAction_(view.value); view.focus(false)})
								.stringColor_( skin.string )
								.background_(skin.background)
								// .maxWidth_(120)
								,
							],
							[
								StaticText()
								.string_("Target")
								.stringColor_(skin.string),

								{
									var previousString = "";
									targetText = TextField()
									.value_(player.target.asString)
									.action_({arg view; player.target_(view.value.interpret); view.focus(false); previousString = view.value;})
									.stringColor_( skin.string )
									.background_(skin.background)
									.focusLostAction_({|view| if(previousString != view.string, {view.doAction})}) //execute when lost focus only if the string changed
									// .maxWidth_(120)
								}.(),
							],
							[
								StaticText()
								.string_("Play rate")
								.stringColor_(skin.string),

								rateNumber = NumberBox()
								.value_(player.rate ?? {1})
								.action_({arg view; player.rate_(view.value)})
								.stringColor_( skin.string )
								.normalColor_( skin.string )
								.clipLo_(0)
								.clipHi_(player.bufsize / (2 * player.server.options.blockSize))
								.background_(skin.background)
							]
						),
						20, //space
						VLayout(//soundfile
							StaticText()
							.string_("Sound File")
							.stringColor_( skin.string)
							.align_(\center),

							Button.new()
							.states_([
								["Open new", skin.string, skin.background]
							])
							.canFocus_(false)
							.action_({
								player.load;
							}),
							Button.new()
							.states_([
								["Reload", skin.string, skin.background]
							])
							.canFocus_(false)
							.action_({player.reset; player.runSetup}),
							nil
						),

						VLayout(//misc
							StaticText()
							.string_("Miscellaneous")
							.stringColor_( skin.string)
							.align_(\center),

							Button.new()
							.states_([
								["Scope On", skin.string, skin.background],
								["Scope Off", skin.string, skin.background]
							])
							.canFocus_(false)
							.action_({arg button;
								[
									{scope.window.close},
									{scope = player.server.scope(player.sf.numChannels, player.outbus)}
								][button.value].value;
							}),
							nil
						),

						nil, //space on the right
						// [nil, stretch: 1] //space on the right?
					).margins_([0, 0, 0, 0])
				).visible_(false)
			);
		);
		// window.front; //
		player.addDependant(this);
		this.loadSF;
		player.cues !? {this.drawCues};
	}

	prScrollAction {arg updateSlider = false; //called by soundfileview, as well as range slider
		var view = sfView;
		var scrollRatio = view.viewFrames/ player.sf.numFrames;
		var start = view.scrollPos.linlin(0, 1, 0, 1 - scrollRatio) * player.sf.duration;
		var end = start + (scrollRatio * player.sf.duration);
		var grid = timeGrid.x.grid;
		grid.spec.minval_(start);
		grid.spec.maxval_(end);
		timeGrid.horzGrid_(grid);
		gridView.refresh;
		cuesView.refresh;
		if(updateSlider, {
			var rangeSize, rangeStart;
			//for zoom slider
			rangeSize = view.xZoom / player.sf.duration;
			rangeStart = view.scrollPos * (1 - rangeSize);
			zoomSlider.lo_(rangeStart).range_(rangeSize);
		});
	}

	advanced {
		^bottomView.visible;
	}

	advanced_ {arg bool;
		bool ?? {bool = this.advanced.not};
		bool = bool.asBoolean;
		advancedButton.value_(bool.asInteger);
		bottomView.visible_(bool)
	}


	getAddActionsArray {
		^Node.addActions.select({|key, val| val.asString.size > 1}).getPairs.clump(2).sort({|a, b| a[1] < b[1]}).flop[0]
	}

	getAddActionIndex {arg addActionArg; //from name or number; this is probably not foolproof...
		if(addActionArg.isKindOf(SimpleNumber), {
			^addActionArg;
		}, {
			//assume name
			^Node.addActions.select({|key, val| val.asString.containsi(addActionArg.asString)}).asArray[0];
		})
	}

	front {
		window !? {window.front};
	}

	playGUIRoutine {
		guiRoutine = Routine.run({
			while( {
				player.isPlaying;
			}, {
				var curTime;
				curTime = player.curTime;
				if(isSelectingNewStartTime.not, {
					sfView.timeCursorPosition_(curTime * player.sf.sampleRate);
					// "(sfView.scrollPos * player.sf.duration - xZoom: ".post; (sfView.scrollPos * player.sf.duration + sfView.xZoom).postln;
					if(autoScroll, {
						if((curTime < (sfView.scrollPos * (player.sf.duration - sfView.xZoom))) || (curTime > ((sfView.scrollPos * (player.sf.duration - sfView.xZoom)) + sfView.xZoom)), {
							sfView.scrollTo(curTime / (player.sf.duration - sfView.xZoom));
							this.prScrollAction(true);
						});
					});
				});
				// timeString.string_(curTime.round(0.01).asTimeString[3..10]);
				this.setTimeString(curTime);
				updateTime.wait;
			});
		}, clock: AppClock)
	}

	stopGUIRoutine {
		guiRoutine.stop;
	}

	hideCues {
		cuesView.drawFunc_({});
		cuesView.refresh;
	}

	setTimeString {arg secs;
		var big, small, str, strBeginning;
		strBeginning = showHours.if({0}, {3});
		str = secs.asTimeString;
		big = str[strBeginning..7];
		small = str[8..10];
		timeString.string_(big);
		timeStringSm.string_(small);
	}

	goToNextCue {
		try{cueMenu.valueAction_((cueMenu.value + 1).min(cueMenu.items.size - 1))};
	}

	goToPreviousCue {
		try{cueMenu.valueAction_((cueMenu.value - 1).max(0))};
	}

	drawCues {
		var points, menuItems, nTabs, inc, cues;
		player.sortCues;
		cues = player.cues;
		cues.notNil.if({
			var drawPointsArr;
			points = cues.collect({arg thisCue;
				thisCue[1];
			});
			drawPointsArr = Array.new(points.size);
			cuesView.drawFunc_({|view|
				var scaledPoints, spec;
				spec = timeGrid.x.grid.spec;
				drawPointsArr = points.collect({arg pt;
					(pt >= spec.minval) && (pt <= spec.maxval)
				});
				scaledPoints = points.collect({arg pt; spec.unmap(pt) * view.bounds.width});
				Pen.font_(Font("Helvetica", 16));
				Pen.strokeColor_(skin.cueLine);
				scaledPoints.do({arg thisPoint, i;
					if(drawPointsArr[i], {
						Pen.moveTo(thisPoint @ 0);
						Pen.lineTo(thisPoint @ (view.bounds.height));
						Pen.stroke;
					});
				});
				Pen.fillColor_(skin.cueLabel);
				scaledPoints.do({arg thisPoint, i;
					if(drawPointsArr[i], {
						Pen.stringAtPoint(cues[i][0].asString,
							(thisPoint + 3) @ (10 + ((i % 10) * 40)));
						Pen.fillStroke;
					});
				});
			});
			menuItems = cues.collect({arg thisCue;
				var key, time, nTabs;
				(thisCue[0].asString.size > 6).if({
					// nTabs = "\t\t"
					nTabs = "  "  //fix for tabs in popupmenu
				}, {
					// nTabs = "\t\t\t"
					nTabs = "      "  //fix for tabs in popupmenu
				});
				thisCue[0].asString + nTabs + thisCue[1].asTimeString;
			});
			// cueMenu.items_(["None" + "\t\t\t" + 0.asTimeString] ++ menuItems);
			cueMenu.items_(["None" + "      " + 0.asTimeString] ++ menuItems); //fix for tabs in popupmenu
			cuesView.refresh;
			// })
		})
	}

	loadSF {
		if(player.sf.notNil, {
			if(player.path.notNil, {
				timeGrid.horzGrid_(ControlSpec(0, player.sf.duration, units: \s).grid);
				gridView.refresh;

				sfView.soundfile_(player.sf);
				sfView.readWithTask(0, player.sf.numFrames, doneAction: {doneAction.value});
				sfView.waveColors_(Array.fill(player.sf.numChannels, skin.sfWaveform));  //set after num channels

				window !? {window.name_(player.path.basename)};
				parent !? {filenameString.string_(player.path.basename)};
				if(player.sf.duration >=3600, {showHours = true}, {showHours = false});
				this.setTimeString(player.startTime);
			});
		})
	}

	update {arg who, what ...args;
		var value = args[0];
		// args[0] is the value
		// format("update fired, who: %, what: %", who, what).postln;
		// what.post;
		// ": ".post; args.postln;
		if(who != this, {
			{
				what.switch(
					\addAction, {addActionMenu.value_(this.getAddActionIndex(value))},
					\target, {targetText.value_(value.asNodeID.asString)},
					\amp, {
						if(args[1] != \number, {
							ampNumber.value_(value.ampdb.round(0.1));
						});
						if(args[1] != \slider, {
							ampSlider.value_(ampSpec.unmap(value.ampdb));
						});
					},
					\outbus, {
						var updMenu = args[1];
						updMenu.if({
							outMenu.value_(value);
							outMenu.focus(false);
						})
					},
					\rate, {
						rateNumber.value_(value);
					},
					\isPlaying, {
						if(value, {
							this.playGUIRoutine;
							playButton.value_(1);
						}, {
							this.stopGUIRoutine;
							playButton.value_(0);
						})
					},
					\isPaused, {
						if(value, {
							pauseButton.value_(1);
						}, {
							pauseButton.value_(0);
						})
					},
					\startTime, {
						sfView.timeCursorPosition_((player.startTime * player.sf.sampleRate).round);
						// timeString.string_(player.startTime.asTimeString[3..10]);
						this.setTimeString(player.startTime);
						outMenu.focus(false);
						// cueOffsetNum.value_(0); //not sure we need this?
						// player.offset = 0;
					},
					\cues, {
						this.drawCues
					},
					\loaded, {
						this.loadSF
					}
				)
			}.defer;
		});
	}
}