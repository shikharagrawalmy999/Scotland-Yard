package bobby;
import java.net.*;
import java.io.*;
import java.util.*;
import java.util.concurrent.Semaphore;
import javax.swing.plaf.synth.SynthPasswordFieldUI;

public class ServerThread implements Runnable{
	private Board board;
	private int id;
	private boolean registered;
	private BufferedReader input;
	private PrintWriter output;
	private Socket socket;
	private int port;
	private int gamenumber;

	public ServerThread(Board board, int id, Socket socket, int port, int gamenumber){
		this.board = board;
		//id from 0 to 4 means detective, -1 means fugitive
		this.id = id;
		this.registered = false;
		this.socket = socket;
		this.port = port;
		this.gamenumber = gamenumber;
	}

	public void run(){

		try{
			board.threadInfoProtector.acquire();
			/*
			PART 0_________________________________
			Set the sockets up
			*/
			input = new BufferedReader(new InputStreamReader(socket.getInputStream()));
			output = new PrintWriter(socket.getOutputStream(), true);			
			
			try{                                               
				if (this.id == -1) {
					output.println(String.format(
							"Welcome. You play Fugitive in Game %d:%d. You start on square 42. Make a move, and wait for feedback",
							this.port, this.gamenumber));
				} else {
					output.println(String.format(
							"Welcome. You play Detective %d in Game %d:%d. You start on square 0. Make a move, and wait for feedback",
							this.id, this.port, this.gamenumber));
				}
			}
			catch (Exception i){ //IOException was there earlier
				/*
				there's no use keeping this thread, so undo what the
				server did when it decided to run it
				*/
				board.erasePlayer(id);               
				return;
			}
			//__________________________________________________________________________________________
			board.threadInfoProtector.release();
			while(true){
				boolean quit = false;
				boolean client_quit = false;
				boolean quit_while_reading = false;
				int target = -1;

				/*
				client_quit means you closed the socket when you read the input,
				check this flag while making a move on the board

				quit means that the thread decides that it will not play the next round

				if quit_while_reading, you just set the board to dead if 
				you're a fugitive. Don't edit the positions on the board, as threads
				are reading

				quit_while_reading is used when you can't call erasePlayer just yet, 
				because the board is being read by other threads

				INVARIANT: 
				quit == client_quit || quit_while_reading

				client_quit && quit_while_reading == false

				DO NOT edit board between barriers!

				erasePlayer, when called by exiting Fugitive, sets 
				this.board.dead to true. Make sure to only alter it during the
				round

				either when
				1) you're about to play but the client had told you to quit
				2) you've played and the game got over in this round.

				________________________________________________________________________________________
				
				First, base case: Fugitive enters the game for the first time
				installPlayer called by a fugitive sets embryo to false.

				Register the Fugitive, install, and enable the moderator, and 
				continue to the next iteration
				*/
				if (this.id == -1 && !this.registered){
					board.moderatorEnabler.release();
					this.registered=true;
					board.registration.acquire();
					board.threadInfoProtector.acquire();
					board.installPlayer(id);
					board.threadInfoProtector.release();                          
					continue;
				}
				board.threadInfoProtector.acquire();
				/*
				Now, usual service
				
				
				PART 1___________________________
				read what the client has to say.
				
				totalThreads and quitThreads are relevant only to the moderator, so we can 
				edit them in the end, just before
				enabling the moderator. (we have the quit flag at our disposal)

				For now, if the player wants to quit,
				just make the id available, by calling erasePlayer.
				this MUST be called by acquiring the threadInfoProtector! 
				
				After that, say goodbye to the client if client_quit
				*/

				String cmd = "";
				try {
					cmd = input.readLine();
				} 
				catch (IOException i) {
					//set flags
					cmd=null;
					client_quit=true;
					quit=true;
				}
				if (cmd == null){
					// rage quit (this would happen if buffer is closed due to SIGINT (Ctrl+C) from Client), set flags
					client_quit=true;
					quit=true;            
                    board.erasePlayer(id);   
					// release everything socket related
                    input.close();
					output.close();
					socket.close();					              
				}
				else if (cmd.equals("Q")) {
					// client wants to disconnect, set flags
					client_quit=true;
					quit=true;  					            
					board.erasePlayer(id);
					// release everything socket related
                    input.close();
					output.close();
					socket.close();					                
				}
				else{
					try{
						//interpret input as the integer target
						target=Integer.parseInt(cmd);
					}
					catch(Exception e){
						//set target that does nothing for a mispressed key
						target = -1;
					}
				}
				/*
				In the synchronization here, playingThreads is sacrosanct.
				DO NOT touch it!
				
				Note that the only thread that can write to playingThreads is
				the Moderator, and it doesn't have the permit to run until we 
				are ready to cross the second barrier.
				____________________________________________________________________________________
				PART 2______________________
				entering the round

				you must acquire the permit the moderator gave you to enter, 
				regardless of whether you're new.

				Also, if you are new, check if the board is dead. If yes, erase the player, set the
				flags, and drop the connection

				Note that installation of a Fugitive sets embryo to false
				*/
				board.threadInfoProtector.release();
				if (!this.registered){
					board.registration.acquire();
					board.threadInfoProtector.acquire();
					this.registered=true;
					board.installPlayer(id);
					if(board.dead){
						board.erasePlayer(id);
						client_quit=true;
						quit=true;
						input.close();
						output.close();
						socket.close();
					}
					board.threadInfoProtector.release();
				}
				/*
				_______________________________________________________________________________________
				PART 3___________________________________
				play the move you read in PART 1 
				if you haven't decided to quit
				else, erase the player
				*/
				
				board.reentry.acquire();
				board.threadInfoProtector.acquire();
				if(!quit){
					if(id==-1){
						board.moveFugitive(target);
					}
					else{
						board.moveDetective(id, target);
					}
				}

				int temp_playing_threads=board.playingThreads;
				board.threadInfoProtector.release();
				/*
				_______________________________________________________________________________________

				PART 4_____________________________________________
				cyclic barrier, first part
				
				execute barrier, so that we wait for all playing threads to play

				Hint: use the count to keep track of how many threads hit this barrier
				they must acquire a permit to cross. The last thread to hit the barrier can 
				release permits for them all.
				*/
				board.countProtector.acquire();
				board.count++;
				if(board.count==temp_playing_threads){
					board.count=0;
					for(int i=0;i<temp_playing_threads;i++){
						board.barrier1.release();
					}
				}
				board.countProtector.release();
				board.barrier1.acquire();
				/*
				________________________________________________________________________________________

				PART 5_______________________________________________
				get the State of the game, and process accordingly. 

				recall that you can only do this if you're not walking away, you took that
				decision in PARTS 1 and 2

				It is here that everyone can detect if the game is over in this round, and decide to quit
				*/
				if (!client_quit){
					String feedback;
					if(id==-1){
						feedback=board.showFugitive();
					}
					else{
						feedback=board.showDetective(id);
					}
					try{
						output.println(feedback);						
					}
					//in case of IO Exception, off with the thread
					catch(Exception i){
						//set flags
						quit_while_reading=true; 
						// If you are a Fugitive you can't edit the board, but you can set dead to true
						if(this.id == -1){  
							board.threadInfoProtector.acquire();                                      
							board.dead=true;
							board.threadInfoProtector.release();
						}
						// release everything socket related
						input.close();
						output.close();
						socket.close(); 
					}
					//parse this feedback to find if game is on
					String indicator;
					String[] tokens=feedback.split(";");
					indicator=tokens[2].trim();  

					if (!indicator.equals("Play")){
						//Proceed simillarly to IOException
						quit_while_reading=true;
                        board.threadInfoProtector.acquire();
						board.dead=true;
						board.threadInfoProtector.release();                                           
						input.close();
						output.close();
						socket.close(); 
					}
				}

				/*
				__________________________________________________________________________________
				PART 6A____________________________
				wrapping up


				everything that could make a thread quit has happened
				now, look at the quit flag, and, if true, make changes in
				totalThreads and quitThreads
				*/
				quit=client_quit||quit_while_reading;
				if(quit){
					board.threadInfoProtector.acquire();
					board.totalThreads--;
					board.quitThreads++;
					board.threadInfoProtector.release();
				}

				/*
				__________________________________________________________________________________
				PART 6B______________________________
				second part of the cyclic barrier
				that makes it reusable
				
				our threads must wait together before proceeding to the next round

				Reuse count to keep track of how many threads hit this barrier2 

				The code is similar. 
				*/
				board.countProtector.acquire();
				board.count++;
				if(board.count==temp_playing_threads){
					board.count=0;
					for(int i=0;i<temp_playing_threads;i++){
						board.barrier2.release();
					}
				}
				board.countProtector.release();
				board.barrier2.acquire();			

				/*
				__________________________________________________________________________________
				PART 6C_________________________________
				actually finishing off a thread
				that decided to quit

				However, the last thread to hit this must issue one
				permit for the moderator to run

				If all else fails use the barriers again
				*/
				board.countProtector.acquire();
				board.count++;
				if(quit){
					if(quit_while_reading){
						board.threadInfoProtector.acquire();
						board.erasePlayer(id);
						board.threadInfoProtector.release();
						input.close();
						output.close();
						socket.close();
					}
				}
				if(board.count==temp_playing_threads){
					board.count=0;
					board.countProtector.release();
					board.moderatorEnabler.release();
				}
				else board.countProtector.release();
				
				if(quit) return;
			}
		}
		catch (InterruptedException ex) {
			return;
		}
		catch (IOException i){
			return;
		}
	}
}